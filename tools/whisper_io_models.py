"""Stand-in Whisper encoder and decoder ONNX models with an AI Hub export's exact I/O (names, element types, shapes), for
Lyrify's WhisperNpuTest: they run on ONNX Runtime's CPU, so the app's WhisperNpu is tested on the laptop end to end
for both the float and the w8a16 export. No weights, nothing of Whisper: each output is a small function of the inputs
that shows whether WhisperNpu fed them as the contract says.

Every input is read in its raw element values (uint8 / uint16 codes, or float16 values) through Cast to float32.

encoder: input_features -> every cross cache filled with one value v: w8a16 floor(max(code) / 256) (uint8);
         float 10 * max(value) (float16).
decoder: self caches out = in + 1 (raw); logits one-hot (cold everywhere, hot at one index):
         index = 1000 + sum over the 8 self caches of (max(in) - zero point)
                      + sum over the 8 cross caches of (max(cross) - zero point)
                      + 100 * attended mask positions + 7 * position_id + 2 * input_id
         where attended = sum(code) / 65535 (w8a16: 0 is 65535, -100 is 0) or sum(value) / 100 + mask length (float).
         hot / cold: w8a16 codes 40000 / zero point; float 5.0 / 0.0.

Usage (the bench's venv, which has onnx): whisper_io_models.py <float metadata.json> <w8a16 metadata.json> <out dir>
"""
import json
import os
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

FLOAT_META, W8A16_META, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
TYPES = {"float16": TensorProto.FLOAT16, "uint8": TensorProto.UINT8, "uint16": TensorProto.UINT16, "int32": TensorProto.INT32}
HOT_CODE = 40000
HOT_FLOAT = 5.0


class Graph:
    def __init__(self) -> None:
        self.nodes, self.inits, self.n = [], [], 0

    def name(self, prefix: str) -> str:
        self.n += 1
        return f"{prefix}_{self.n}"

    def const(self, value, dtype=np.float32) -> str:
        name = self.name("c")
        self.inits.append(numpy_helper.from_array(np.array(value, dtype=dtype), name))
        return name

    def op(self, op: str, inputs: list, **attrs) -> str:
        out = self.name(op.lower())
        self.nodes.append(helper.make_node(op, inputs, [out], **attrs))
        return out

    def out(self, op: str, inputs: list, name: str, **attrs) -> None:
        self.nodes.append(helper.make_node(op, inputs, [name], **attrs))

    def raw_max(self, x: str) -> str:
        """max over every element of x, as float32 (a scalar)."""
        return self.op("ReduceMax", [self.op("Cast", [x], to=TensorProto.FLOAT)], keepdims=0)


def io(meta: dict, model: str, side: str):
    return meta["model_files"][model][side]


def value_info(name: str, t: dict):
    return helper.make_tensor_value_info(name, TYPES[t["dtype"]], t["shape"])


def zero_point(t: dict) -> float:
    return float(t.get("quantization_parameters", {}).get("zero_point", 0))


def encoder(meta: dict, quantized: bool) -> onnx.ModelProto:
    g = Graph()
    inputs, outputs = io(meta, "encoder.onnx", "inputs"), io(meta, "encoder.onnx", "outputs")
    m = g.raw_max("input_features")
    if quantized:
        v = g.op("Cast", [g.op("Floor", [g.op("Div", [m, g.const(256.0)])])], to=TensorProto.UINT8)
    else:
        v = g.op("Cast", [g.op("Mul", [m, g.const(10.0)])], to=TensorProto.FLOAT16)
    for name, t in outputs.items():
        g.out("Expand", [v, g.const(t["shape"], np.int64)], name)
    graph = helper.make_graph(g.nodes, "whisper_encoder_standin", [value_info(n, t) for n, t in inputs.items()],
                              [value_info(n, t) for n, t in outputs.items()], g.inits)
    return helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], producer_name="whisper_io_models")


def decoder(meta: dict, quantized: bool) -> onnx.ModelProto:
    g = Graph()
    inputs, outputs = io(meta, "decoder.onnx", "inputs"), io(meta, "decoder.onnx", "outputs")
    terms = [g.const(1000.0)]
    for name, t in inputs.items():
        if "cache" in name:
            terms.append(g.op("Sub", [g.raw_max(name), g.const(zero_point(t))]))
    mask_len = inputs["attention_mask"]["shape"][-1]
    mask_sum = g.op("ReduceSum", [g.op("Cast", ["attention_mask"], to=TensorProto.FLOAT)], keepdims=0)
    if quantized:
        attended = g.op("Div", [mask_sum, g.const(65535.0)])
    else:
        attended = g.op("Add", [g.op("Div", [mask_sum, g.const(100.0)]), g.const(float(mask_len))])
    terms.append(g.op("Mul", [attended, g.const(100.0)]))
    terms.append(g.op("Mul", [g.raw_max("position_ids"), g.const(7.0)]))
    terms.append(g.op("Mul", [g.raw_max("input_ids"), g.const(2.0)]))
    index = g.op("Sum", terms)
    vocab = outputs["logits"]["shape"][1]
    logits = io(meta, "decoder.onnx", "outputs")["logits"]
    cold, hot = (zero_point(logits), float(HOT_CODE)) if quantized else (0.0, HOT_FLOAT)
    onehot = g.op("OneHot", [g.op("Reshape", [index, g.const([1], np.int64)]), g.const(float(vocab)), g.const([cold, hot])], axis=-1)
    shaped = g.op("Reshape", [onehot, g.const(logits["shape"], np.int64)])
    g.out("Cast", [shaped], "logits", to=TYPES[logits["dtype"]])
    for name, t in outputs.items():
        if name == "logits":
            continue
        source = name.replace("_out", "_in")
        if quantized:
            plus = g.op("Add", [g.op("Cast", [source], to=TensorProto.INT32), g.const(1, np.int32)])
        else:
            plus = g.op("Add", [g.op("Cast", [source], to=TensorProto.FLOAT), g.const(1.0)])
        g.out("Cast", [plus], name, to=TYPES[t["dtype"]])
    graph = helper.make_graph(g.nodes, "whisper_decoder_standin", [value_info(n, t) for n, t in inputs.items()],
                              [value_info(n, t) for n, t in outputs.items()], g.inits)
    return helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], producer_name="whisper_io_models")


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    for path, precision in ((FLOAT_META, "float"), (W8A16_META, "w8a16")):
        meta = json.load(open(path, encoding="utf-8"))
        assert meta["precision"] == precision, meta["precision"]
        for part, build in (("encoder", encoder), ("decoder", decoder)):
            model = build(meta, precision == "w8a16")
            # IR 8 with opset 17, as the spike's uint_io.onnx: well within what ONNX Runtime 1.30.0 reads.
            model.ir_version = 8
            onnx.checker.check_model(model, full_check=True)
            onnx.save(model, os.path.join(OUT, f"whisper_standin_{precision}_{part}.onnx"))


main()
