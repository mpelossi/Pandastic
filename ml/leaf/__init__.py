"""Leaf classifier pipeline: download -> manifest -> train -> calibrate -> thresholds -> ONNX + JSON.

Runs on Modal (ml/modal_app.py) or locally on CPU (python -m leaf.smoke). Output has the same
inputs, outputs and file names as the stub, so the app picks it up by replacing the stub files.
"""
