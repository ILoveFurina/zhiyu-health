"""泛型懒构造包装：首次调用才构建生产 delegate。

注入装配路径（测试用 SQLite + 不配置方舟环境变量）因此不依赖 settings，
只有真正命中接口才读取真实配置。
"""

from collections.abc import Callable


class LazyDelegate[T]:
    """首次 get() 时经工厂构建并缓存 delegate，之后复用同一实例。"""

    def __init__(self, factory: Callable[[], T]) -> None:
        self._factory = factory
        self._delegate: T | None = None

    def get(self) -> T:
        if self._delegate is None:
            self._delegate = self._factory()
        return self._delegate
