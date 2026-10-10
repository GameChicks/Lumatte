# Room schemas

Room Gradle 插件已将本目录设为 schemaDirectory。当前未获准编译，尚未生成数据库 schema JSON。

首次获准编译后，将实际生成的版本 1 schema 纳入 Git。后续数据库升级提供明确迁移，不启用删除数据的 fallback，也不以手工占位 JSON 冒充编译器生成结果。
