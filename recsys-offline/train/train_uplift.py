#!/usr/bin/env python3
"""A7 TARNet + IPW uplift 训练，输出 mu0/mu1 ONNX；不接受缺控制组/无 overlap 的伪因果数据。"""
import argparse
import json
import os
import sys
from datetime import datetime, timezone

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import torch.nn.functional as F

HERE=os.path.dirname(os.path.abspath(__file__))
ROOT=os.path.normpath(os.path.join(HERE,"..",".."))
MODEL_DIR=os.path.join(ROOT,"recsys-ad","src","main","resources","model")
DENSE=["pctr","pcvr","quality","relevance","log_bid"]
USER_BUCKETS,ITEM_BUCKETS,AD_BUCKETS,ADV_BUCKETS=5000,20000,10000,5000


def cli():
    p=argparse.ArgumentParser(); p.add_argument("--samples",default=os.path.join(HERE,"ad_uplift_samples.csv"))
    p.add_argument("--epochs",type=int,default=40); p.add_argument("--model-version",default="a7-"+datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")); return p.parse_args()


class TarNet(nn.Module):
    def __init__(self):
        super().__init__(); cards=[USER_BUCKETS,ITEM_BUCKETS,AD_BUCKETS,ADV_BUCKETS]
        self.emb=nn.ModuleList([nn.Embedding(c,8) for c in cards])
        self.body=nn.Sequential(nn.Linear(32+len(DENSE),64),nn.ReLU(),nn.Linear(64,32),nn.ReLU())
        self.h0=nn.Sequential(nn.Linear(32,16),nn.ReLU(),nn.Linear(16,1))
        self.h1=nn.Sequential(nn.Linear(32,16),nn.ReLU(),nn.Linear(16,1))
    def forward(self,dense,sparse):
        x=torch.cat([self.emb[i](sparse[:,i]) for i in range(4)]+[dense],dim=1); h=self.body(x)
        return torch.sigmoid(self.h0(h)),torch.sigmoid(self.h1(h))


def main():
    a=cli()
    if not os.path.exists(a.samples): sys.exit(f"找不到 {a.samples};先跑 --job=gen-ad-uplift-samples")
    df=pd.read_csv(a.samples); required={"outcome","treatment","propensity","user_id","item_id","ad_id","advertiser_id","pctr","pcvr","quality","relevance","bid","split"}
    if not required.issubset(df.columns): sys.exit(f"uplift samples 缺列 {sorted(required-set(df.columns))}")
    t=df.treatment.to_numpy(dtype="float32"); y=df.outcome.to_numpy(dtype="float32"); e=df.propensity.to_numpy(dtype="float32")
    if len(np.unique(t)) != 2: sys.exit("因果红线:样本必须同时有 treatment/control")
    if np.any(e<=0.01) or np.any(e>=0.99): sys.exit("因果红线:propensity 无 overlap 或接近 0/1")
    if y[t==1].sum()==0 or y[t==0].sum()==0: sys.exit("因果红线:两臂都必须存在正 outcome")
    dense=np.stack([df.pctr,df.pcvr,df.quality,df.relevance,np.log1p(np.maximum(df.bid,0))],axis=1).astype("float32")
    sparse=np.stack([df.user_id%USER_BUCKETS,df.item_id%ITEM_BUCKETS,df.ad_id%AD_BUCKETS,df.advertiser_id%ADV_BUCKETS],axis=1).astype("int64")
    train=(df.split=="train").to_numpy(); valid=~train
    if train.sum()<10 or valid.sum()<2: sys.exit("train/valid 样本不足")
    xd=torch.from_numpy(dense); xs=torch.from_numpy(sparse); yt=torch.from_numpy(y).view(-1,1); tt=torch.from_numpy(t).view(-1,1); et=torch.from_numpy(e).view(-1,1)
    torch.manual_seed(42); model=TarNet(); opt=torch.optim.Adam(model.parameters(),lr=1e-3,weight_decay=1e-5)
    tr=torch.from_numpy(np.where(train)[0]); va=torch.from_numpy(np.where(valid)[0]); best=None; best_loss=float("inf"); bad=0
    for epoch in range(1,a.epochs+1):
        model.train(); perm=tr[torch.randperm(len(tr))]; total=0.0
        for start in range(0,len(perm),512):
            idx=perm[start:start+512]; mu0,mu1=model(xd[idx],xs[idx]); factual=tt[idx]*mu1+(1-tt[idx])*mu0
            weight=tt[idx]/et[idx]+(1-tt[idx])/(1-et[idx]); loss=(weight*F.binary_cross_entropy(factual,yt[idx],reduction="none")).mean()
            opt.zero_grad();loss.backward();opt.step();total+=loss.item()*len(idx)
        model.eval()
        with torch.no_grad():
            m0,m1=model(xd[va],xs[va]); factual=tt[va]*m1+(1-tt[va])*m0
            weight=tt[va]/et[va]+(1-tt[va])/(1-et[va]); vl=(weight*F.binary_cross_entropy(factual,yt[va],reduction="none")).mean().item()
        print(f"epoch {epoch:02d} train_ipw={total/len(tr):.5f} valid_ipw={vl:.5f}")
        if vl<best_loss-1e-5: best_loss=vl;best={k:v.detach().clone() for k,v in model.state_dict().items()};bad=0
        else:
            bad+=1
            if bad>=6: break
    model.load_state_dict(best);model.eval()
    with torch.no_grad(): m0,m1=model(xd[va],xs[va]); delta=(m1-m0).squeeze(1).numpy()
    metrics=uplift_metrics(delta,y[valid],t[valid],e[valid]); print("metrics",json.dumps(metrics,ensure_ascii=False))
    os.makedirs(MODEL_DIR,exist_ok=True); path=os.path.join(MODEL_DIR,"model_uplift.onnx")
    torch.onnx.export(model,(torch.zeros(2,5),torch.zeros(2,4,dtype=torch.int64)),path,input_names=["dense","sparse"],output_names=["mu0","mu1"],dynamic_axes={"dense":{0:"N"},"sparse":{0:"N"},"mu0":{0:"N"},"mu1":{0:"N"}},opset_version=17,dynamo=False)
    import onnx; graph=onnx.load(path);graph.ir_version=9;onnx.save_model(graph,path,save_as_external_data=False)
    schema={"contract_version":1,"algorithm":"tarnet_ipw","model_version":a.model_version,"dense_order":DENSE,"sparse_order":["user_id","item_id","ad_id","advertiser_id"],"user_buckets":USER_BUCKETS,"item_buckets":ITEM_BUCKETS,"ad_buckets":AD_BUCKETS,"advertiser_buckets":ADV_BUCKETS,"metrics":metrics}
    with open(os.path.join(MODEL_DIR,"uplift_schema.json"),"w") as f:json.dump(schema,f,ensure_ascii=False,indent=2)
    import onnxruntime as ort; sess=ort.InferenceSession(path,providers=["CPUExecutionProvider"]); out=sess.run(None,{"dense":dense[:4],"sparse":sparse[:4]})
    if any(x.shape!=(min(4,len(df)),1) for x in out) or not all(np.isfinite(x).all() for x in out):sys.exit("uplift ONNX 回读失败")
    print(f"uplift ONNX 回读 OK;version={a.model_version};samples={len(df)}")


def uplift_metrics(delta,y,t,e):
    order=np.argsort(-delta); transformed=y*(t/e-(1-t)/(1-e)); gain=np.cumsum(transformed[order]); auuc=float(gain.mean()); random=float(gain[-1]/2); qini=auuc-random
    k=max(1,len(order)//10); top=order[:k]; policy=float(np.mean(y[top]*t[top]/e[top]-y[top]*(1-t[top])/(1-e[top])))
    return {"auuc":auuc,"qini":qini,"top_decile_ipw_value":policy,"mean_uplift":float(delta.mean())}


if __name__=="__main__":main()
