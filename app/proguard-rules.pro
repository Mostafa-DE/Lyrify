# Lyrify release shrinking rules.
#
# The activity and the notification listener are referenced from the manifest, which AGP
# keeps automatically. kotlinx.serialization, DataStore, coroutines and Compose ship their
# own consumer rules.
#
# ONNX Runtime's JNI library finds its Java classes by name (OnnxTensor, OrtSession,
# OrtException, OrtEpDevice...), but the AAR's consumer rules only keep its telemetry classes.
# The QNN execution provider is a native plugin registered by file path: no Java classes.
-keep class ai.onnxruntime.** { *; }

# The app's one use of reflection: app.lyrify.ai.IoTensors makes the w8a16 Whisper's uint16
# tensors through these package-private and private members of ONNX Runtime 1.30.0's Java API
# (checked against java/src/main/java/ai/onnxruntime at the v1.30.0 tag). The rule above keeps
# them already; these name exactly what IoTensors looks up, so narrowing it cannot drop them.
# IoTensorsTest pins the ONNX Runtime version: on a bump, check these against the new sources.
-keep class ai.onnxruntime.OnnxTensor {
    private static native long createTensorFromBuffer(long, long, java.nio.Buffer, int, long, long[], int);
    <init>(long, long, ai.onnxruntime.TensorInfo, java.nio.Buffer, boolean);
}
-keep class ai.onnxruntime.OnnxRuntime {
    static long ortApiHandle;
}
-keep class ai.onnxruntime.OrtEnvironment {
    final ai.onnxruntime.OrtAllocator defaultAllocator;
}
-keep class ai.onnxruntime.OrtAllocator {
    final long handle;
}
-keep class ai.onnxruntime.TensorInfo {
    <init>(long[], ai.onnxruntime.OnnxJavaType, ai.onnxruntime.TensorInfo$OnnxTensorType);
}
#
# Add -keep rules here if a future change loads classes by name.
