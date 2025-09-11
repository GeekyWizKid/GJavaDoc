# MyBatis扫描配置选项

## 📋 新增配置说明

在GJavaDoc插件设置的 **Context** 标签页中，新增了专门的MyBatis配置选项，让用户能够精细控制MyBatis扫描行为。

## ⚙️ 配置选项详解

### 基础控制

1. **Enable MyBatis scanning / 启用MyBatis扫描**
   - 默认值：`true`
   - 作用：全局开启/关闭MyBatis扫描功能
   - 禁用后将完全跳过MyBatis XML和BaseMapper扫描

2. **Include XML mappings / 包含XML映射**
   - 默认值：`true`  
   - 作用：控制是否扫描MyBatis XML映射文件
   - 禁用后仅扫描Java注解形式的MyBatis方法

3. **Include MyBatis-Plus BaseMapper / 包含MyBatis-Plus BaseMapper**
   - 默认值：`true`
   - 作用：控制是否扫描MyBatis-Plus的BaseMapper方法
   - 禁用后将忽略所有继承自BaseMapper的默认CRUD方法

### 智能映射控制

4. **Strict service mapping / 严格服务映射**
   - 默认值：`true`
   - 作用：控制MyBatis映射与服务类的关联策略
   - 启用时：仅扫描与 `@RpcService` 等服务类相关的MyBatis映射
   - 禁用时：扫描所有MyBatis映射（回到全局扫描模式）

### 精细化配置

5. **Mapper Suffixes / Mapper后缀**
   - 默认值：`"Mapper,DAO"`
   - 作用：定义Mapper类的命名后缀，用于关联策略判断
   - 格式：逗号分隔的后缀列表

6. **XML Scan Paths / XML扫描路径**
   - 默认值：`"src/main/resources,src/test/resources"`
   - 作用：指定XML文件扫描的根路径
   - 格式：逗号分隔的相对路径列表

## 🎯 使用场景与建议

### 场景1：标准企业项目
```
✅ Enable MyBatis scanning: true
✅ Include XML mappings: true  
✅ Include MyBatis-Plus BaseMapper: true
✅ Strict service mapping: true
```
适合大多数企业项目，确保只文档化与服务入口点相关的数据访问层。

### 场景2：纯注解项目
```
✅ Enable MyBatis scanning: true
❌ Include XML mappings: false
✅ Include MyBatis-Plus BaseMapper: true  
✅ Strict service mapping: true
```
适合使用MyBatis注解或MyBatis-Plus而不使用XML映射的项目。

### 场景3：遗留系统全面文档化
```
✅ Enable MyBatis scanning: true
✅ Include XML mappings: true
✅ Include MyBatis-Plus BaseMapper: true
❌ Strict service mapping: false
```
适合需要为遗留系统生成完整数据访问文档的情况。

### 场景4：关闭MyBatis扫描
```
❌ Enable MyBatis scanning: false
```
适合不使用MyBatis或希望专注于服务层文档的项目。

## 💡 最佳实践

### 1. 项目初期设置
- 建议保持默认配置（严格服务映射开启）
- 确保 `@RpcService` 等服务入口注解配置正确
- 验证扫描结果是否符合预期

### 2. 关联策略优化
如果发现重要的Mapper未被扫描到，可以：
- 检查服务类和Mapper类的包结构关系
- 调整 `Mapper Suffixes` 配置包含项目特有的命名模式
- 临时禁用 `Strict service mapping` 进行全局扫描对比

### 3. 性能考虑
- 大型项目建议保持 `Strict service mapping` 开启
- `XML Scan Paths` 可以限制为实际包含映射文件的目录
- 定期清理不相关的XML文件以提高扫描效率

## 🔧 配置访问

### 在设置界面
1. **IntelliJ IDEA** → **Preferences/Settings**
2. 选择 **Tools** → **GJavaDoc**  
3. 切换到 **Context** 标签页
4. 在 **MyBatis Configuration** 部分调整选项

### 在代码中访问
```kotlin
val settings = SettingsState.getInstance(project).state
if (settings.mybatis.enabled) {
    // MyBatis扫描已启用
    val strictMapping = settings.mybatis.strictServiceMapping
    val includeXml = settings.mybatis.includeXmlMappings
    // ...
}
```

---

通过这些精细化的配置选项，用户可以根据具体项目需求灵活控制MyBatis扫描的行为，在文档完整性和性能效率之间找到最佳平衡点。