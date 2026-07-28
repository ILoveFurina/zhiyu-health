# Domain Docs

本仓库采用 single-context 领域文档布局。工程技能探索代码库前，应按以下规则读取和使用领域资料。

## Before exploring, read these

- 根目录的 `CONTEXT.md`
- `docs/adr/` 中与即将处理区域相关的 ADR

如果这些文件不存在，静默继续，不要预先建议创建。`/domain-modeling` 会在术语或决策真正得到解决时按需创建或更新它们。

## File structure

```text
/
├── CONTEXT.md
├── docs/
│   └── adr/
└── server-java/、server-py/、admin/、miniprogram/
```

## Use the glossary's vocabulary

在票单标题、重构建议、假设和测试名称中命名领域概念时，使用 `CONTEXT.md` 定义的术语，不要改用其明确避免的同义词。

如果所需概念尚未进入词汇表，应先判断是否正在创造项目没有使用的语言；如果确属领域缺口，则记录下来交由 `/domain-modeling` 处理。

## Flag ADR conflicts

如果输出与现有 ADR 冲突，必须明确指出冲突及理由，不得静默覆盖既有决策。
