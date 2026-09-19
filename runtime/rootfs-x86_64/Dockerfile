FROM --platform=linux/amd64 debian:bookworm-slim

ARG DEBIAN_FRONTEND=noninteractive
ARG DXVK_VERSION=1.10.3
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

COPY packages.txt /tmp/vodka/packages.txt
COPY overlay/ /tmp/vodka/overlay/

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      $(grep -vE '^[[:space:]]*(#|$)' /tmp/vodka/packages.txt | tr '\n' ' ') \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/vodka/dxvk \
 && curl -L --fail -o /tmp/dxvk.tar.gz \
      "https://github.com/doitsujin/dxvk/releases/download/v${DXVK_VERSION}/dxvk-${DXVK_VERSION}.tar.gz" \
 && tar -xzf /tmp/dxvk.tar.gz -C /tmp \
 && cp -a "/tmp/dxvk-${DXVK_VERSION}/x64/." /opt/vodka/dxvk/ \
 && rm -rf /tmp/dxvk*

RUN mkdir -p /usr/share/vodka \
 && printf 'amd64\n' > /usr/share/vodka/arch \
 && printf 'bookworm\n' > /usr/share/vodka/suite \
 && printf '%s\n' "$DXVK_VERSION" > /usr/share/vodka/dxvk-version \
 && cp -a /tmp/vodka/overlay/. / \
 && rm -rf /tmp/vodka

CMD ["/bin/bash"]
