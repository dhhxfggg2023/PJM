#!/system/bin/sh
# ============================================
# PJM 特权服务启动脚本
# 用法: adb shell sh /sdcard/Android/data/com.dhhxfggg.pjm/start.sh
# 以 shell (UID 2000) 身份运行内置文件服务，突破 Android/data 访问限制
# ============================================
APP_PACKAGE=com.dhhxfggg.pjm

# IO 目录（app 外部私有目录，app 与 shell 均可读写）
IO_DIR="/sdcard/Android/data/$APP_PACKAGE/files/io"
mkdir -p "$IO_DIR"

# 获取 PJM APK 路径
APK_PATH=$(cmd package path $APP_PACKAGE | cut -d ':' -f2)
if [ -z "$APK_PATH" ]; then
  echo "[PJM] 错误: $APP_PACKAGE 未安装"
  exit 1
fi

# 检查是否已在运行
if [ -f /data/local/tmp/pjm_server.pid ]; then
  OLD_PID=$(cat /data/local/tmp/pjm_server.pid 2>/dev/null)
  if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    echo "[PJM] 特权服务已在运行 (pid=$OLD_PID)"
    exit 0
  fi
fi

echo "[PJM] 启动特权服务 (APK: $APK_PATH)"
# 后台启动，不占用终端
nohup app_process -Djava.class.path="$APK_PATH" /system/bin --nice-name=pjm_privileged_server \
  com.dhhxfggg.pjm.domain.shizuku.FileServerMain "$IO_DIR" > /data/local/tmp/pjm_server.log 2>&1 &

# 记录 PID
echo $! > /data/local/tmp/pjm_server.pid

# 等待就绪
sleep 1
if kill -0 "$!" 2>/dev/null; then
  echo "[PJM] 特权服务已启动 (pid=$!)"
  echo "[PJM] 日志: /data/local/tmp/pjm_server.log"
else
  echo "[PJM] 启动失败，查看日志: /data/local/tmp/pjm_server.log"
  exit 1
fi
