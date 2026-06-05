# A-eye Native Model Assets

Expected files:

- `yolov8n_coco80_float32.tflite`: active YOLOv8n COCO 80-class TFLite model.
- `yolov8n_demo.tflite`: previous small person-only demo model.
- `coco.txt`: COCO 80 class labels, one label per line.

`YoloDetector` reads the first output tensor and supports common YOLOv8 TFLite output layouts:

- `[1, 84, 8400]`
- `[1, 8400, 84]`

The active model was verified with this shape:

- input: `[1, 640, 640, 3]` float32
- output: `[1, 84, 8400]` float32

If a teammate provides a compressed or quantized model, replace `YoloDetector.MODEL_FILE` and keep the output format compatible or adjust `YoloDetector.parseOutput`.
