FROM --platform=linux/arm64 ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

COPY packages.txt /tmp/vodka/packages.txt
COPY overlay/ /tmp/vodka/overlay/

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      $(grep -vE '^[[:space:]]*(#|$)' /tmp/vodka/packages.txt | tr '\n' ' ') \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /proc /sys /dev /run /tmp /usr/share/vodka \
 && chmod 1777 /tmp \
 && printf 'arm64\n' > /usr/share/vodka/arch \
 && printf 'noble\n' > /usr/share/vodka/suite \
 && cp -a /tmp/vodka/overlay/. / \
 && rm -rf /tmp/vodka

RUN mkdir -p /usr/lib/x86_64-linux-gnu \
 && ln -sfn /opt/vodka/rootfs-x86_64/usr/share/wine /usr/share/wine \
 && ln -sfn /opt/vodka/rootfs-x86_64/usr/lib/x86_64-linux-gnu/wine /usr/lib/x86_64-linux-gnu/wine \
 && mkdir -p /opt \
 && ln -sfn /opt/vodka/rootfs-x86_64/opt/kombucha /opt/kombucha \
 && ( [ -s /etc/machine-id ] || printf '0123456789abcdef0123456789abcdef\n' > /etc/machine-id ) \
 && chmod 755 /usr/local/bin/vodka-input

CMD ["/bin/bash"]
