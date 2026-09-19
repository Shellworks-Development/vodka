FROM --platform=linux/arm64 debian:bookworm-slim

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
 && printf 'bookworm\n' > /usr/share/vodka/suite \
 && cp -a /tmp/vodka/overlay/. / \
 && rm -rf /tmp/vodka

RUN mkdir -p /usr/lib/x86_64-linux-gnu \
 && ln -sfn /opt/vodka/rootfs-x86_64/usr/share/wine /usr/share/wine \
 && ln -sfn /opt/vodka/rootfs-x86_64/usr/lib/x86_64-linux-gnu/wine /usr/lib/x86_64-linux-gnu/wine

CMD ["/bin/bash"]
