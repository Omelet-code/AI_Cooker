# 扩展源版本固定；sha值必须由来源核验/构建前提供，缺失即失败。
ARG PGVECTOR_IMAGE
FROM ${PGVECTOR_IMAGE}
ARG SCWS_SHA256
ARG ZHPARSER_SHA256
COPY deploy/vendor/scws-1.2.3.tar.bz2 /tmp/scws.tar.bz2
COPY deploy/vendor/zhparser-2.3.tar.gz /tmp/zhparser.tar.gz
RUN test -n "$SCWS_SHA256" && test -n "$ZHPARSER_SHA256" \
 && echo "$SCWS_SHA256  /tmp/scws.tar.bz2" | sha256sum -c - \
 && echo "$ZHPARSER_SHA256  /tmp/zhparser.tar.gz" | sha256sum -c - \
 && apt-get update && apt-get install -y --no-install-recommends build-essential bzip2 ca-certificates postgresql-server-dev-18 \
 && mkdir /tmp/scws /tmp/zhparser \
 && tar xjf /tmp/scws.tar.bz2 -C /tmp/scws --strip-components=1 \
 && tar xzf /tmp/zhparser.tar.gz -C /tmp/zhparser --strip-components=1 \
 && cd /tmp/scws && ./configure && make && make install \
 && make -C /tmp/zhparser && make -C /tmp/zhparser install \
 && rm -rf /tmp/scws /tmp/zhparser /tmp/*.tar.* /var/lib/apt/lists/*
