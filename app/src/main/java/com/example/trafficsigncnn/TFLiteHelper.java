package com.example.trafficsigncnn;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.List;

public class TFLiteHelper {

    private Interpreter interpreter;
    private List<String> labels;
    private int inputHeight;
    private int inputWidth;
    private DataType inputDataType;
    private DataType outputDataType;
    private int[] outputShape;

    public TFLiteHelper(Context context) throws IOException {
        // Load model from assets
        MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, "custom_cnn_v1_fp16.tflite");
        interpreter = new Interpreter(modelBuffer);

        // Load labels from assets
        labels = FileUtil.loadLabels(context, "labels.txt");

        // Inspect input tensor details dynamically
        int[] inputShape = interpreter.getInputTensor(0).shape(); // expected [1, height, width, channels]
        inputHeight = inputShape[1];
        inputWidth = inputShape[2];
        inputDataType = interpreter.getInputTensor(0).dataType();

        // Inspect output tensor details dynamically
        outputShape = interpreter.getOutputTensor(0).shape(); // expected [1, num_classes]
        outputDataType = interpreter.getOutputTensor(0).dataType();
    }

    public InferenceResult classifyImage(Bitmap bitmap) {
        if (interpreter == null || labels == null) {
            return null;
        }

        // 1. Prepare input image wrapper
        TensorImage inputImage = new TensorImage(inputDataType);
        inputImage.load(bitmap);

        // 2. Preprocess: Resize to model's expected size and Normalize pixels to [0.0, 1.0]
        // NormalizeOp(mean, std) -> (value - mean) / std. Setting mean=0.0f, std=255.0f scales to [0.0, 1.0].
        ImageProcessor imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                .add(new NormalizeOp(0.0f, 255.0f))
                .build();
        inputImage = imageProcessor.process(inputImage);

        // 3. Create output buffer matching the model's output tensor details
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(outputShape, outputDataType);

        // 4. Run inference
        interpreter.run(inputImage.getBuffer(), outputBuffer.getBuffer().rewind());

        // 5. Find the class with the highest probability/confidence
        float[] probabilities = outputBuffer.getFloatArray();
        int maxIndex = -1;
        float maxConfidence = -1.0f;

        for (int i = 0; i < probabilities.length; i++) {
            if (probabilities[i] > maxConfidence) {
                maxConfidence = probabilities[i];
                maxIndex = i;
            }
        }

        // 6. Map the index to the corresponding label
        String label = "Unknown";
        if (maxIndex >= 0 && maxIndex < labels.size()) {
            label = labels.get(maxIndex);
        }

        return new InferenceResult(label, maxConfidence);
    }

    public Interpreter getInterpreter() {
        return interpreter;
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}