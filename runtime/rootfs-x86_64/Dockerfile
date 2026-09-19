FROM --platform=linux/amd64 ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
ARG DXVK_VERSION=2.4
ARG KOMBUCHA_TAG=proton-stable+20260914101957
ARG WINE_MONO_VERSION=10.4.1
ARG WINE_GECKO_VERSION=2.47.4
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

RUN mkdir -p /usr/share/wine/mono /usr/share/wine/gecko \
 && curl -L --fail -o "/usr/share/wine/mono/wine-mono-${WINE_MONO_VERSION}-x86.msi" \
      "https://dl.winehq.org/wine/wine-mono/${WINE_MONO_VERSION}/wine-mono-${WINE_MONO_VERSION}-x86.msi" \
 && curl -L --fail -o "/usr/share/wine/gecko/wine-gecko-${WINE_GECKO_VERSION}-x86_64.msi" \
      "https://dl.winehq.org/wine/wine-gecko/${WINE_GECKO_VERSION}/wine-gecko-${WINE_GECKO_VERSION}-x86_64.msi" \
 && curl -L --fail -o "/usr/share/wine/gecko/wine-gecko-${WINE_GECKO_VERSION}-x86.msi" \
      "https://dl.winehq.org/wine/wine-gecko/${WINE_GECKO_VERSION}/wine-gecko-${WINE_GECKO_VERSION}-x86.msi"

RUN mkdir -p /opt/vodka/prefix \
 && export WINEPREFIX=/opt/vodka/prefix WINEARCH=win64 WINEDEBUG=-all \
      WINEDLLPATH=/opt/kombucha/lib/wine/x86_64-unix:/opt/kombucha/lib/wine/x86_64-windows \
      WINEDATADIR=/opt/kombucha/share/wine ; \
    /opt/kombucha/bin/wine wineboot -u >/tmp/wineboot.log 2>&1 || true ; \
    /opt/kombucha/bin/wineserver -k >/dev/null 2>&1 || true ; \
    if [ ! -d /opt/vodka/prefix/drive_c ]; then echo "PREFIX BUILD FAILED"; cat /tmp/wineboot.log 2>/dev/null; exit 1; fi

RUN curl -L --fail -o /usr/local/bin/winetricks \
      "https://raw.githubusercontent.com/Winetricks/winetricks/master/src/winetricks" \
 && chmod 755 /usr/local/bin/winetricks

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
