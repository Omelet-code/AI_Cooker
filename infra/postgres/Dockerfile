FROM pgvector/pgvector:pg18

# 词法检索使用数据库侧中文分词，镜像固定构建扩展，避免运行时在生产数据库中临时编译。
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential bzip2 ca-certificates curl git postgresql-server-dev-18 \
    && curl -fsSL http://www.xunsearch.com/scws/down/scws-1.2.3.tar.bz2 | tar xj -C /tmp \
    && cd /tmp/scws-1.2.3 && ./configure && make && make install \
    && git clone --depth 1 --branch v2.3 https://github.com/amutu/zhparser.git /tmp/zhparser \
    && make -C /tmp/zhparser \
    && make -C /tmp/zhparser install \
    && rm -rf /var/lib/apt/lists/* /tmp/scws-1.2.3 /tmp/zhparser
