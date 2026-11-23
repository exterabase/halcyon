ARG UBUNTU=ubuntu:24.04@sha256:9c7f200c07e3d4a03e7249d2c16bf66a51120fb876aa2242cf31451412543289
FROM ${UBUNTU}
RUN --mount=type=bind,target=/source --mount=type=secret,id=pip_ca \
    if [ -f /run/secrets/pip_ca ]; then export PIP_CERT=/run/secrets/pip_ca; fi; \
    export DEBIAN_FRONTEND=noninteractive; \
    apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates git python3 python3-venv gcc libc6-dev python3-dev && \
    mkdir -p /data /opt/halcyon /tmp/halcyon-build/vendor && \
    cp /source/requirements.txt /tmp/halcyon-build/ && \
    cp -a /source/vendor/telegram /tmp/halcyon-build/vendor/ && \
    cd /tmp/halcyon-build && python3 -m venv /opt/halcyon-venv && \
    CFLAGS='-O1 -g0' /opt/halcyon-venv/bin/python -m pip install --no-cache-dir -r requirements.txt && \
    /opt/halcyon-venv/bin/python -m pip check && \
    /opt/halcyon-venv/bin/python -c 'from halcyon_tl._security import SecurityGate; from halcyon_tl.sessions import SQLiteSession' && \
    apt-get purge -y --auto-remove gcc libc6-dev python3-dev && apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/halcyon-build
