FROM --platform=linux/amd64 ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
ARG DXVK_VERSION=2.4
ARG KOMBUCHA_TAG=proton-stable+20260914101957
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

COPY packages.txt /tmp/vodka/packages.txt
COPY overlay/ /tmp/vodka/overlay/

RUN dpkg --add-architecture i386 \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      $(grep -vE '^[[:space:]]*(#|$)' /tmp/vodka/packages.txt | tr '\n' ' ') \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/kombucha \
 && curl -L --fail -o /tmp/kombucha.tar.xz \
      "https://github.com/vinegarhq/kombucha/releases/download/${KOMBUCHA_TAG}/kombucha-${KOMBUCHA_TAG}.tar.xz" \
 && tar -xJf /tmp/kombucha.tar.xz -C /opt/kombucha --strip-components=1 \
 && rm /tmp/kombucha.tar.xz

RUN curl -L --fail -o /usr/local/bin/winetricks \
      "https://raw.githubusercontent.com/Winetricks/winetricks/master/src/winetricks" \
 && chmod 755 /usr/local/bin/winetricks

RUN ln -sfn /usr/lib/wine/wine64 /usr/bin/wine \
 && ln -sfn /usr/lib/wine/wine64 /usr/lib/wine/wine \
 && ln -sfn /usr/lib/wine/wineserver64 /usr/bin/wineserver \
 && ln -sfn /usr/lib/wine/wineserver64 /usr/lib/wine/wineserver

RUN mkdir -p /opt/vodka/dxvk \
 && curl -L --fail -o /tmp/dxvk.tar.gz \
      "https://github.com/doitsujin/dxvk/releases/download/v${DXVK_VERSION}/dxvk-${DXVK_VERSION}.tar.gz" \
 && tar -xzf /tmp/dxvk.tar.gz -C /tmp \
 && cp -a "/tmp/dxvk-${DXVK_VERSION}/x64/." /opt/vodka/dxvk/ \
 && rm -rf /tmp/dxvk*

RUN mkdir -p /usr/share/vodka \
 && printf 'amd64\n' > /usr/share/vodka/arch \
 && printf 'noble\n' > /usr/share/vodka/suite \
 && printf '%s\n' "$DXVK_VERSION" > /usr/share/vodka/dxvk-version \
 && printf '%s\n' "$KOMBUCHA_TAG" > /usr/share/vodka/kombucha-version \
 && cp -a /tmp/vodka/overlay/. / \
 && rm -rf /tmp/vodka

CMD ["/bin/bash"]
