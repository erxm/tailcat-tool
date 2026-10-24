#!/system/bin/sh
#
# Tailcat 服务端在 Android 上的启动/停止脚本。
#
# 用法（在 adb shell 里，或 adb shell sh /data/local/tmp/ct.sh <命令>）：
#   ct.sh start [端口]   启动服务端（默认 5555，即把本机 adbd 暴露出去）
#   ct.sh stop           停止
#   ct.sh status         查看是否在跑 + 打印当前 tc 地址
#   ct.sh addr           只打印 tc 地址
#   ct.sh log            看服务端日志
#
# 需要先做到：
#   adb push tailcat /data/local/tmp/tailcat && adb shell chmod 755 /data/local/tmp/tailcat
#   adb tcpip 5555        # 让 adbd 监听 TCP，否则没有可暴露的端点
#
# 注意：tc 地址本身就是凭据（内含预共享密钥），不要贴到公开场合。

BIN=/data/local/tmp/tailcat
LOG=/data/local/tmp/tailcat-serve.log
PIDFILE=/data/local/tmp/tailcat-serve.pid
# v0.7.0 的 serve 参数是纯端口号，端口会被代理到本机 localhost 的同号端口。
# （README 里 `5555:10.2.200.213:5555` 那种带主机的映射形式在当前版本已不支持。）
DEFAULT_MAP=5555

running() {
    [ -f "$PIDFILE" ] || return 1
    pid=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$pid" ] || return 1
    # /proc/<pid>/cmdline 里能看到 tailcat 才算真的活着
    [ -r "/proc/$pid/cmdline" ] || return 1
    grep -q tailcat "/proc/$pid/cmdline" 2>/dev/null
}

addr() {
    [ -f "$LOG" ] || return 1
    sed -n 's/.*Server listening[^:]*: \(tc[A-Za-z0-9_-]*\).*/\1/p' "$LOG" | tail -1
}

case "$1" in
start)
    if running; then
        echo "已在运行 (pid $(cat $PIDFILE))"
        addr
        exit 0
    fi
    [ -x "$BIN" ] || { echo "找不到 $BIN，请先 adb push 并 chmod 755"; exit 1; }
    map=${2:-$DEFAULT_MAP}
    : > "$LOG"
    # nohup 让进程在 adb shell 断开后继续活着；stdin 接 /dev/null 避免被当成前台交互
    nohup "$BIN" serve "$map" >> "$LOG" 2>&1 < /dev/null &
    echo $! > "$PIDFILE"
    # 等它打印出地址（首次要连 DERP，通常 1-3 秒）
    i=0
    while [ $i -lt 40 ]; do
        a=$(addr)
        [ -n "$a" ] && { echo "pid $(cat $PIDFILE)"; echo "$a"; exit 0; }
        sleep 0.25
        i=$((i + 1))
    done
    echo "启动超时，日志如下："
    cat "$LOG"
    exit 1
    ;;
stop)
    if running; then
        pid=$(cat "$PIDFILE")
        kill "$pid" 2>/dev/null
        sleep 1
        kill -9 "$pid" 2>/dev/null
        echo "已停止 pid $pid"
    else
        echo "没有在运行"
    fi
    rm -f "$PIDFILE"
    ;;
status)
    if running; then
        echo "运行中 (pid $(cat $PIDFILE))"
        addr
    else
        echo "未运行"
    fi
    ;;
addr)
    addr
    ;;
log)
    cat "$LOG"
    ;;
*)
    echo "用法: $0 {start [端口]|stop|status|addr|log}"
    exit 2
    ;;
esac
