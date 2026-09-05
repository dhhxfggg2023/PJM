# Room Schema 导出目录

此目录存放 Room 编译生成的数据库 schema JSON（`com.dhhxfggg.pjm.data.db.AppDatabase/N.json`），用于：

1. **代码审查**：每次数据库结构变更可清晰 diff 出表/索引变化；
2. **编写显式 Migration**：从旧版本 JSON 精确知道要 `ALTER` 什么；
3. **Migration 自动化测试**：用旧 schema 建库 → 跑 Migration → 断言新 schema 与数据正确。

> 规则：请把生成的 `N.json` **提交到版本库**。任何 `version` 变更必须同时在
> [AppDatabase.MIGRATIONS] 注册显式 Migration，禁止回退到 destructive migration。
