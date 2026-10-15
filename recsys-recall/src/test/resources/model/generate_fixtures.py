"""为持久化集成测试生成确定性向量；不用于验证训练质量或生产推理精度。

手工重建：recsys-offline/train/.venv/bin/python <此文件>。
测试执行只需已提交的固件，不依赖 Python 或训练产物。
"""
from pathlib import Path

import onnx
from onnx import TensorProto, helper


def save(name, input_name, input_shape, output_name, output_shape, values):
    tensor = helper.make_tensor("fixed_vectors", TensorProto.FLOAT, output_shape, values)
    graph = helper.make_graph(
        [helper.make_node("Constant", [], [output_name], value=tensor)],
        name,
        [helper.make_tensor_value_info(input_name, TensorProto.INT64, input_shape)],
        [helper.make_tensor_value_info(output_name, TensorProto.FLOAT, output_shape)],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], ir_version=8)
    onnx.checker.check_model(model)
    onnx.save(model, Path(__file__).with_name(name + "-fixture.onnx"))


save("tower", "user_bucket", [1, 1], "user_vec", [1, 64], [1.0] + [0.0] * 63)
save("mind", "history_items", [1, 50], "interests", [1, 4, 64],
     [float(column == row) for row in range(4) for column in range(64)])

# 动态输入长度覆盖逐步生成；每层固定偏好code=0，测试三级前缀回退的数据库行为。
tiger_nodes = [
    helper.make_node("Shape", ["tokens"], ["input_shape"]),
    helper.make_node("Constant", [], ["vocab_size"], value=helper.make_tensor("size", TensorProto.INT64, [1], [8])),
    helper.make_node("Concat", ["input_shape", "vocab_size"], ["output_shape"], axis=0),
    helper.make_node("Constant", [], ["fixed_logits"], value=helper.make_tensor(
        "logits", TensorProto.FLOAT, [1, 1, 8], [0, 0, 5, 0, 5, 0, 5, 0])),
    helper.make_node("Expand", ["fixed_logits", "output_shape"], ["logits"]),
]
tiger_graph = helper.make_graph(tiger_nodes, "tiger", [helper.make_tensor_value_info(
    "tokens", TensorProto.INT64, ["batch", "length"])], [helper.make_tensor_value_info(
    "logits", TensorProto.FLOAT, ["batch", "length", 8])])
tiger = helper.make_model(tiger_graph, opset_imports=[helper.make_opsetid("", 17)], ir_version=8)
onnx.checker.check_model(tiger)
onnx.save(tiger, Path(__file__).with_name("tiger-fixture.onnx"))
