"""应用日志装配：接管 uvicorn 不覆盖的 app.* logger。

uvicorn 默认只给 uvicorn.* 装 handler，root 无 handler，app.* 的 INFO/DEBUG
会被 lastResort 静默吞掉（票 33 排查时 server-py 侧无日志可看的直接原因）。
basicConfig 幂等：root 已有 handler 时不再重复添加，测试多次 create_app 安全。
"""

import logging


def configure_logging() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
