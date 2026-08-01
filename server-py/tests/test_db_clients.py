"""libpq 连接串归一化：.env 的 DATABASE_URL 可能是 SQLAlchemy 风格 scheme。"""

from app.db.clients import libpq_dsn


def test_sqlalchemy_scheme_is_rewritten_to_libpq() -> None:
    assert (
        libpq_dsn("postgresql+psycopg://user:pass@host:5432/db")
        == "postgresql://user:pass@host:5432/db"
    )


def test_standard_schemes_pass_through() -> None:
    assert libpq_dsn("postgresql://user:pass@host/db") == "postgresql://user:pass@host/db"
    assert libpq_dsn("postgres://user:pass@host/db") == "postgres://user:pass@host/db"


def test_keyword_conninfo_passes_through() -> None:
    assert libpq_dsn("host=localhost dbname=zhiyu") == "host=localhost dbname=zhiyu"
