#!/usr/bin/env bash
# ============================================================
#  AI-Order-Assistant agent-service 启动脚本（Git Bash / Linux / macOS）
#  自动使用 conda 环境 ai-order-agent，无需手动 activate
# ============================================================
set -e
cd "$(dirname "$0")"

CONDA_BASE="$(conda info --base 2>/dev/null || true)"
if [ -z "$CONDA_BASE" ]; then
  echo "[ERROR] 未找到 conda，请先安装 Anaconda / Miniconda 并加入 PATH"
  exit 1
fi

# Windows 环境变量是 python.exe，Linux/macOS 是 python
if [ -f "$CONDA_BASE/envs/ai-order-agent/python.exe" ]; then
  ENV_PY="$CONDA_BASE/envs/ai-order-agent/python.exe"
else
  ENV_PY="$CONDA_BASE/envs/ai-order-agent/bin/python"
fi

if [ ! -x "$ENV_PY" ]; then
  echo "[ERROR] 找不到 conda 环境 ai-order-agent：$ENV_PY"
  echo
  echo "请先创建环境（二选一）："
  echo "  方式A：conda env create -f environment.yml"
  echo "  方式B：conda create -n ai-order-agent python=3.13 -y && pip install -r requirements.txt"
  exit 1
fi

echo "[INFO] 使用 conda 环境 Python：$ENV_PY"
echo "[INFO] 启动 agent-service，监听 http://127.0.0.1:8800 （Ctrl+C 停止）"
exec "$ENV_PY" -m uvicorn app.main:app --host 127.0.0.1 --port 8800 --reload
