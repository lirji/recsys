"""测试模型把首个序列ID与序列长度作为输出，验证SQL排序、过滤及广播。

手工重建使用 recsys-offline/train/.venv/bin/python；测试运行不依赖Python。
"""
from pathlib import Path

import onnx
from onnx import TensorProto, helper

nodes = [
    helper.make_node("Constant", [], ["first"], value=helper.make_tensor("first", TensorProto.INT64, [1], [0])),
    helper.make_node("Constant", [], ["scale"], value=helper.make_tensor("scale", TensorProto.FLOAT, [1], [100])),
    helper.make_node("Gather", ["seq", "first"], ["first_id"], axis=1),
    helper.make_node("Cast", ["first_id"], ["first_float"], to=TensorProto.FLOAT),
    helper.make_node("Div", ["first_float", "scale"], ["ctr"]),
    helper.make_node("Cast", ["seq_len"], ["len_float"], to=TensorProto.FLOAT),
    helper.make_node("Div", ["len_float", "scale"], ["cvr"]),
]
inputs = [helper.make_tensor_value_info(name, kind, shape) for name, kind, shape in [
    ("dense", TensorProto.FLOAT, ["batch", 5]), ("sparse", TensorProto.INT64, ["batch", 3]),
    ("seq", TensorProto.INT64, ["batch", "length"]), ("seq_len", TensorProto.INT64, ["batch"]),
]]
outputs = [helper.make_tensor_value_info("ctr", TensorProto.FLOAT, ["batch", 1]),
           helper.make_tensor_value_info("cvr", TensorProto.FLOAT, ["batch"])]
model = helper.make_model(helper.make_graph(nodes, "sequence-observer", inputs, outputs),
                          opset_imports=[helper.make_opsetid("", 17)], ir_version=8)
onnx.checker.check_model(model)
onnx.save(model, Path(__file__).with_name("sequence-fixture.onnx"))
