import os
import sys
from pathlib import Path

# ai-python/ 을 sys.path 에 추가해 flat import(guardrails, main, prompts)를 가능하게 한다.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
os.environ.setdefault("INTERNAL_TOKEN", "test-internal-token")

# ★ 테스트는 어떤 경우에도 유료 API 를 호출하지 않는다.
#   llm.get_provider() 는 ANTHROPIC_API_KEY 가 있으면 AnthropicProvider 를 돌려준다.
#   test_main.py 는 9곳에서 /internal/ai/analyze-and-draft 를 부르고, 그중에는
#   risk 3 경로(T3=opus)도 있다. 키가 환경에 있는 채로 pytest 를 돌리면 그대로 과금된다.
#   provider 를 monkeypatch 하는 테스트는 한 곳뿐이라 나머지는 무방비다.
#   골든셋 평가(eval.py)는 키를 쓰지만 그건 사람이 명시적으로 켜는 별도 경로다.
#   CLAUDE.md "DataAPI 를 실제 호출하는 테스트는 CI 에서 금지한다" 와 같은 원칙이다.
os.environ.pop("ANTHROPIC_API_KEY", None)
