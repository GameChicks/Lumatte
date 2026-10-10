# Room schemas

Room Gradle 插件已将本目录设为 schemaDirectory。当前未获准编译，尚未生成数据库 schema JSON。

当前数据库源码版本为 3；已提供 1 → 2 的 savedUri 列迁移、2 → 3 的接收记录表及 cameraAcknowledgement 列迁移，旧版本也未编译过。首次获准编译后，将实际生成的当前 schema 纳入 Git；不伪造历史 schema。后续数据库升级提供明确迁移，不启用删除数据的 fallback，也不以手工占位 JSON 冒充编译器生成结果。
