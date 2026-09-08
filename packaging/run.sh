set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$ROOT/observec.jar"
BIN_DIR="$ROOT/bin"
export OBSERVEC_DATA_DIR="${OBSERVEC_DATA_DIR:-$ROOT/observecData}"
export PATH="$BIN_DIR:$PATH"
JAVA_OPTS="${JAVA_OPTS:- -Xms256m -Xmx1g}"

JAVA_OPTS_ARR=($JAVA_OPTS)
PORT="${PORT:-8080}"

fail=0
for cmd in java git pgrep pkill; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required command: $cmd" >&2; fail=1; }
done
for b in prometheus blackbox_exporter alertmanager; do
  [ -x "$BIN_DIR/$b" ] || { echo "missing bundled binary: $BIN_DIR/$b" >&2; fail=1; }
done
[ -f "$JAR" ] || { echo "missing jar: $JAR" >&2; fail=1; }
if [ "$fail" -ne 0 ]; then
  echo "Install hints:" >&2
  echo "  Arch:   sudo pacman -S --needed jre17-openjdk git procps-ng" >&2
  echo "  RHEL:   sudo dnf install -y java-17-openjdk git procps-ng" >&2
  echo "  Debian: sudo apt-get install -y openjdk-17-jre-headless git procps" >&2
  exit 1
fi

JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1)"
if [ "$JAVA_MAJOR" -lt 17 ]; then
  echo "java 17+ required, found: $(java -version 2>&1 | head -1)" >&2
  exit 1
fi

mkdir -p \
  "$OBSERVEC_DATA_DIR/blackbox/blackboxData" \
  "$OBSERVEC_DATA_DIR/blackbox/tls" \
  "$OBSERVEC_DATA_DIR/prometheus/prometheusData" \
  "$OBSERVEC_DATA_DIR/prometheus/serviceDiscoveryFiles" \
  "$OBSERVEC_DATA_DIR/prometheus/ruleFiles/monitors" \
  "$OBSERVEC_DATA_DIR/prometheus/kubeConfigs" \
  "$OBSERVEC_DATA_DIR/alertmanager/alertmanagerData" \
  "$OBSERVEC_DATA_DIR/monitors" \
  "$OBSERVEC_DATA_DIR/thanos"

git config --global --add safe.directory "$OBSERVEC_DATA_DIR" 2>/dev/null || true

D="$OBSERVEC_DATA_DIR"
exec java "${JAVA_OPTS_ARR[@]}" -jar "$JAR" \
  --server.port="$PORT" \
  --observec.data-dir="$D" \
  --observec.auth.db-path="$D/users.db" \
  --observec.git.repo-path="$D" \
  --observec.prometheus.config-path="$D/prometheus/prometheusConfig.yml" \
  --observec.prometheus.log-path="$D/prometheus/prometheus.log" \
  --observec.prometheus.data-dir="$D/prometheus/prometheusData" \
  --observec.prometheus.rule-files-dir="$D/prometheus/ruleFiles" \
  --observec.prometheus.service-discovery-dir="$D/prometheus/serviceDiscoveryFiles" \
  --observec.prometheus.kube-configs-dir="$D/prometheus/kubeConfigs" \
  --observec.blackbox.config-path="$D/blackbox/blackboxConfig.yml" \
  --observec.blackbox.log-path="$D/blackbox/blackbox.log" \
  --observec.blackbox.tls-data-dir="$D/blackbox/tls" \
  --observec.alertmanager.config-path="$D/alertmanager/alertmanagerConfig.yml" \
  --observec.alertmanager.log-path="$D/alertmanager/alertmanager.log" \
  --observec.monitor.store-path="$D/monitors/monitors.json"
