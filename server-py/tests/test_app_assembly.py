"""生产与测试应用装配保持单向隔离。"""

import os
from pathlib import Path
import subprocess
import sys


def test_importing_test_app_does_not_build_production_app() -> None:
    server_py = Path(__file__).resolve().parents[1]
    environment = {**os.environ, "PYTHONPATH": str(server_py)}
    probe = (
        "import sys; import app.testing; "
        "assert 'app.bootstrap' not in sys.modules; "
        "assert 'app.main' not in sys.modules"
    )

    result = subprocess.run(
        [sys.executable, "-c", probe],
        cwd=server_py.parent,
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 0, result.stderr
