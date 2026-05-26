#!/bin/sh

aarch64-linux-gnu-gcc -c src/bcenabler_patch.s -o /tmp/patch.o
aarch64-linux-gnu-objcopy -O binary -j .text /tmp/patch.o /tmp/patch.bin

# Must be smaller than a single page
bin2c -o gen/bcenabler_patch.h -n PatchRawData /tmp/patch.bin

rm /tmp/patch.o
rm /tmp/patch.bin
