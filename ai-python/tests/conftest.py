import sys
from pathlib import Path

# ai-python/ 을 sys.path 에 추가해 flat import(guardrails, main, prompts)를 가능하게 한다.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
