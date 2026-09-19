package com.jmxlogger.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 构建期信息：git describe、版本号、commit、提交时间。
 *
 * <p>有两个来源，按"信息越准越优先"叠加：
 * <ul>
 *   <li>{@code app.properties}：构建时由 maven 过滤 {@code ${project.version}} 生成，
 *       <b>只要有 pom 就必然存在</b>（没有 .git 的源码包/CI 归档也有）；</li>
 *   <li>{@code git.properties}：由 {@code git-commit-id-maven-plugin} 生成，只在有 .git 时存在，
 *       提供 {@code git describe} 与提交时间。</li>
 * </ul>
 *
 * <p>{@code -V} 的输出形态（对外契约，改动要同步 README）：
 * <ul>
 *   <li>有 {@code git.commit.id.describe}：{@code jmx-logger v1.0.0-21-gcb6fe8ed+ (20260919)}；</li>
 *   <li>没有（无 .git / 浅克隆 / 无 tag）：{@code jmx-logger 1.0.0}；</li>
 *   <li>连过滤后的属性文件都没有（IDE 直接跑未编译的 main）：降级文案。</li>
 * </ul>
 *
 * <p>红线：取不到构建信息时一律返回 {@link #UNKNOWN}，
 * <b>绝不因为读不到资源就让 {@code -V} 抛异常</b>。
 */
public final class BuildInfo {

    /** 取不到构建信息时的占位值。 */
    public static final String UNKNOWN = "unknown";

    private static final String RESOURCE_APP = "/app.properties";
    private static final String RESOURCE_GIT = "/git.properties";
    private static final String KEY_DESCRIBE = "git.commit.id.describe";
    private static final String KEY_VERSION_GIT = "git.build.version";
    /** 由 maven 资源过滤写进 app.properties，无 .git 的构建也一定有。 */
    private static final String KEY_VERSION_APP = "app.version";
    private static final String KEY_COMMIT = "git.commit.id.abbrev";
    private static final String KEY_COMMIT_TIME = "git.commit.time";
    private static final String KEY_BUILD_TIME = "git.build.time";
    private static final String KEY_DIRTY = "git.dirty";

    private final String gitDescribe;
    private final String version;
    private final String commitId;
    private final String commitTime;
    private final String buildTime;
    private final boolean dirty;

    private BuildInfo(String gitDescribe, String version, String commitId,
                      String commitTime, String buildTime, boolean dirty) {
        this.gitDescribe = gitDescribe;
        this.version = version;
        this.commitId = commitId;
        this.commitTime = commitTime;
        this.buildTime = buildTime;
        this.dirty = dirty;
    }

    /**
     * 读 classpath 上的构建信息：先铺 {@code app.properties}（兜底版本号），
     * 再用 {@code git.properties} 覆盖/补充（describe、commit）。两个都读不到就是全 unknown。
     */
    public static BuildInfo load() {
        Properties props = new Properties();
        loadInto(props, RESOURCE_APP);
        loadInto(props, RESOURCE_GIT);
        return from(props);
    }

    /** 从已解析的属性构造，供测试直接喂样本，不必依赖真实构建产物。 */
    static BuildInfo from(Properties props) {
        return new BuildInfo(
                value(props, KEY_DESCRIBE),
                firstKnown(value(props, KEY_VERSION_GIT), value(props, KEY_VERSION_APP)),
                value(props, KEY_COMMIT),
                value(props, KEY_COMMIT_TIME),
                value(props, KEY_BUILD_TIME),
                "true".equalsIgnoreCase(value(props, KEY_DIRTY)));
    }

    private static void loadInto(Properties props, String resource) {
        InputStream in = BuildInfo.class.getResourceAsStream(resource);
        if (in == null) {
            return;
        }
        try {
            props.load(in);
        } catch (IOException e) {
            // 构建信息只是锦上添花，读失败不该影响 CLI 正常工作
        } finally {
            closeQuietly(in);
        }
    }

    private static String firstKnown(String primary, String fallback) {
        return UNKNOWN.equals(primary) ? fallback : primary;
    }

    private static String value(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            return UNKNOWN;
        }
        value = value.trim();
        // 资源过滤没跑（IDE 直接跑未过滤的 resources）时会留下 ${project.version} 原样
        return value.contains("${") ? UNKNOWN : value;
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    /** {@code git describe} 结果，形如 {@code v1.0.0-21-gcb6fe8ed+}（结尾 {@code +} 表示工作区未提交）。 */
    public String gitDescribe() {
        return gitDescribe;
    }

    public String version() {
        return version;
    }

    /** commit 短哈希。 */
    public String commitId() {
        return commitId;
    }

    public String commitTime() {
        return commitTime;
    }

    public String buildTime() {
        return buildTime;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** 是否有可用的构建信息（两个资源都缺失时为 false）。 */
    public boolean isKnown() {
        return !UNKNOWN.equals(gitDescribe) || !UNKNOWN.equals(version) || !UNKNOWN.equals(commitId);
    }

    /**
     * {@code -V} 的一行输出：优先 describe（含 tag 距离与 commit），
     * 退化到 pom 版本号，再退化到"构建信息不可用"。
     */
    public String versionLine() {
        if (!UNKNOWN.equals(gitDescribe)) {
            // jmx-logger v1.0.0-21-gcb6fe8ed+ (20260919)
            return UNKNOWN.equals(commitTime)
                    ? "jmx-logger " + gitDescribe
                    : "jmx-logger " + gitDescribe + " (" + commitTime + ")";
        }
        if (!UNKNOWN.equals(version)) {
            return "jmx-logger " + version;
        }
        return "jmx-logger (构建信息不可用：未找到版本属性文件)";
    }
}
