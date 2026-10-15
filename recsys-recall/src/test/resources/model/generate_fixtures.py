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
