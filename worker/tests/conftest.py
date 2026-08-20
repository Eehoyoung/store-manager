import sys
from pathlib import Path

# worker/ 를 sys.path 에 추가해 flat import(dataapi, celery_app, tasks)를 가능하게 한다.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
