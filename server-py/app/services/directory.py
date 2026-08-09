"""兼容导入；标准科室适配器与 Agent 工具统一位于 ``app.tools.department``。"""

from app.tools.department import CallbackDepartmentDirectory, DepartmentDirectory

__all__ = ["CallbackDepartmentDirectory", "DepartmentDirectory"]
