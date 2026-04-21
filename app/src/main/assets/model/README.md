# PyTorch Model Placement Guide

## Where to place your model

Put your exported TorchScript Lite model file right here:

```
app/src/main/assets/model/driver_model.ptl
```

## How to export your model from Python

```python
import torch
from torch.utils.mobile_optimizer import optimize_for_mobile

# 1. Instantiate your trained model
model = YourDriverModel()
model.load_state_dict(torch.load("best_weights.pth"))
model.eval()

# 2. Script or trace it
#    Option A — Script (preferred if your model has no dynamic control flow issues):
scripted = torch.jit.script(model)

#    Option B — Trace (if scripting fails):
#    example_input = torch.randn(1, 250, 6)  # [batch, seq_len, features]
#    scripted = torch.jit.trace(model, example_input)

# 3. Optimize for mobile and save as .ptl (Lite format)
optimized = optimize_for_mobile(scripted)
optimized._save_for_lite_interpreter("driver_model.ptl")
```

## Expected input / output shapes

| Direction | Shape            | Description                                       |
|-----------|------------------|---------------------------------------------------|
| **Input** | `[1, SeqLen, 6]` | SeqLen = number of sensor readings in 5 seconds.  |
|           |                  | 6 channels = accelX, accelY, accelZ, gyroX, gyroY, gyroZ |
| **Output**| `[1, 5]`         | 5-class logits. argmax + 1 = rating (1..5).       |

- **Rating 1** = Rash / dangerous driving
- **Rating 5** = Smooth / professional driving

## No model? No problem!

If this directory is empty, the app automatically falls back to a
**heuristic mode** that estimates a rating from accelerometer magnitude.
This lets you test the full UI and sensor pipeline without a trained model.
