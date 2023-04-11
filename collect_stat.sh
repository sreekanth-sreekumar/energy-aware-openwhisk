#!/bin/bash

LOG_FILE=/home/pi/system_stats.log
SLEEP_TIME=0.05

while true; do
  CPU_UTILIZATION=$(top -bn1 | grep "Cpu(s)" | sed "s/.*, *\([0-9.]*\)%* id.*/\1/" | awk '{print 100 - $1}')
  MEMORY_USAGE=$(free -m | awk 'NR==2{printf "%s/%sMB (%.2f%%)", $3,$2,$3*100/$2 }')
  TEMPERATURE=$(vcgencmd measure_temp | sed "s/temp=//")

  # Estimate power drawn based on CPU utilization and clock speed
  CLOCK_SPEED=$(vcgencmd measure_clock arm | awk -F"=" '{printf "%d",$2}')
  POWER_DRAWN=$(echo "scale=2; ($CLOCK_SPEED / 1000000 * $CPU_UTILIZATION * 0.0007) + 1.2" | bc)

  TIMESTAMP=$(date +"%Y-%m-%d %H:%M:%S.%N" | awk '{printf "%s%03d\n", $0, ($2 / 1000000)}')
  LOG_LINE="$TIMESTAMP: Cpu: $CPU_UTILIZATION%, Memory: $MEMORY_USAGE, Temp: $TEMPERATURE, Power: $POWER_DRAWN W"

  echo $LOG_LINE >> $LOG_FILE

  sleep $SLEEP_TIME
done
