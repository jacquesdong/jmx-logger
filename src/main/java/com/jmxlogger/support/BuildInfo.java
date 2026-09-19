package com.jmxlogger.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 构建期信息：版本号、commit、构建时间、工作区是否干净。
 *
 * <p>来源是 classpath 上的 {@code git.properties}，由构建期的
 * {@code git-commit-id-maven-plugin} 生成（见 pom.xml）。这样拿到一个 fat jar 时，
 * 用 {@code -V} 就能回答"这个 jar 是哪次提交打的"，而不是永远只显示 pom 里的 1.0.0。
 *
 * <p>红线：没有 {@code .git} 的构建（源码包、CI 归档）插件会跳过、根本不生成该文件；
 * 本机 IDE 直接跑 main 也可能没有。这些情形下本类一律返回 {@link #UNKNOWN}，
 * <b>绝不因为取不到构建信息就让 {@code -V} 抛异常</b>。
 */
public final class BuildInfo {

    /** 取不到构建信息时的占位值。 */
    public static final String UNKNOWN = "unknown";

    private static final String RESOURCE = "/git.properties";
    private static final String KEY_VERSION = "git.build.version";
    private static final String KEY_COMMIT = "git.commit.id.abbrev";
    private static final String KEY_COMMIT_TIME = "git.commit.time";
    private static final String KEY_BUILD_TIME = "git.build.time";
    private static final String KEY_DIRTY = "git.dirty";

    private final String version;
    private final String commitId;
    private final String commitTime;
    private final String buildTime;
    private final boolean dirty;

    private BuildInfo(String version, String commitId, String commitTime, String buildTime, boolean dirty) {
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
                value(props, KEY_VERSION),
                value(props, KEY_COMMIT),
                value(props, KEY_COMMIT_TIME),
                value(props, KEY_BUILD_TIME),
                "true".equalsIgnoreCase(value(props, KEY_DIRTY)));
    }

    private static BuildInfo unknown() {
        return new BuildInfo(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, false);
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

    public String version() {
        return version;
    }

    /** commit 的短哈希；{@code +} 后缀表示构建时工作区有未提交改动。 */
    public String commitId() {
        return dirty ? commitId + "+" : commitId;
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
        return !UNKNOWN.equals(version) || !UNKNOWN.equals(commitId);
    }

    /** {@code -V} 的一行输出。 */
    public String describe() {
        if (!isKnown()) {
            return "jmx-logger (构建信息不可用：未找到 git.properties)";
        }
        StringBuilder sb = new StringBuilder("jmx-logger ").append(version);
        if (!UNKNOWN.equals(commitId)) {
            sb.append(" (commit ").append(commitId());
            if (!UNKNOWN.equals(buildTime)) {
                sb.append(", 构建于 ").append(buildTime);
            }
            sb.append(")");
        }
        return sb.toString();
    }
}
