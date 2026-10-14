package cc.coauto.tailcat.core.model

/**
 * 当前墙钟毫秒。这是整个 core 里唯一的 `expect/actual`——
 * 其余平台差异（进程、凭据、路径、可执行文件查找、端口）都走接口注入，
 * 这样测试替身与演示环境才有多种实现可用。
 */
public expect fun nowMillis(): Long
