package com.jmxlogger.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 构建期信息：git describe、版本号、commit、提交时间。
 *
 * <p>来源是 classpath 上的 {@code git.properties}，由构建期的
 * {@code git-commit-id-maven-plugin} 生成（见 pom.xml）。这样拿到一个 fat jar 时，
 * 用 {@code -V} 就能回答"这个 jar 是哪次提交打的"，而不是永远只显示 pom 里的 1.0.0。
 *
 * <p>{@code -V} 的输出形态（对外契约，改动要同步 README）：
 * <ul>
 *   <li>有 {@code git.commit.id.describe}：{@code jmx-logger v1.0.0-21-gcb6fe8ed+ (20260919)}；</li>
 *   <li>没有（浅克隆/无 tag）：{@code jmx-logger 1.0.0}；</li>
 *   <li>连 {@code git.properties} 都没有：降级文案。</li>
 * </ul>
 *
 * <p>红线：没有 {@code .git} 的构建（源码包、CI 归档）插件会跳过、根本不生成该文件；
 * 本机 IDE 直接跑 main 也可能没有。这些情形下本类一律返回 {@link #UNKNOWN}，
 * <b>绝不因为取不到构建信息就让 {@code -V} 抛异常</b>。
 */
public final class BuildInfo {

    /** 取不到构建信息时的占位值。 */
    public static final String UNKNOWN = "unknown";

    private static final String RESOURCE = "/git.properties";
    private static final String KEY_DESCRIBE = "git.commit.id.describe";
    private static final String KEY_VERSION = "git.build.version";
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

    /** 读 classpath 上的 {@code git.properties}；读不到（无文件/IO 失败）返回全 unknown。 */
    public static BuildInfo load() {
        InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            return unknown();
        }
        try {
            Properties props = new Properties();
            props.load(in);
            return from(props);
        } catch (IOException e) {
            // 构建信息只是锦上添花，读失败不该影响 CLI 正常工作
            return unknown();
        } finally {
            closeQuietly(in);
        }
    }

    /** 从已解析的属性构造，供测试直接喂样本，不必依赖真实构建产物。 */
    static BuildInfo from(Properties props) {
        return new BuildInfo(
                value(props, KEY_DESCRIBE),
                value(props, KEY_VERSION),
                value(props, KEY_COMMIT),
                value(props, KEY_COMMIT_TIME),
                value(props, KEY_BUILD_TIME),
                "true".equalsIgnoreCase(value(props, KEY_DIRTY)));
    }

    private static BuildInfo unknown() {
        return new BuildInfo(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, false);
    }

    private static String value(Properties props, String key) {
        String value = props.getProperty(key);
        return value == null || value.isEmpty() ? UNKNOWN : value.trim();
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

    /** 是否有可用的构建信息（无 {@code git.properties} 时为 false）。 */
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
        return "jmx-logger (构建信息不可用：未找到 git.properties)";
    }
}
