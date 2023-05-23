#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
FROM ubuntu:latest

# Install required packages
RUN apt update && apt upgrade -y
RUN apt install -y locales && \
  localedef -i en_US -c -f UTF-8 -A /usr/share/locale/locale.alias en_US.UTF-8

# Set environment variables
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

RUN apt install curl -y
RUN find / -name bash -type f

# Install the JDK using apk package manager
RUN apt-get update && apt-get install openjdk-8-jdk -y --fix-missing

# Install Scala 2.13.5 We have no choice but to do this since we can't find a compatible image for both amd64 and arm/v7
ENV SCALA_VERSION=2.12.10
ENV SCALA_HOME=/usr/share/scala

RUN curl -fsL "https://downloads.typesafe.com/scala/$SCALA_VERSION/scala-$SCALA_VERSION.tgz" | tar xfz - -C /usr/share \
  && chown -R root:root /usr/share/scala-$SCALA_VERSION \
  && ln -s /usr/share/scala-$SCALA_VERSION/bin/* /usr/local/bin/ \
  && rm -f /usr/local/bin/scala /usr/local/bin/scalac \
  && rm -rf /usr/share/man /tmp/*

# Create logs directory
RUN mkdir /logs

# Copy the scripts into the container
COPY transformEnvironment.sh /
COPY copyJMXFiles.sh /

# Make the scripts executable
RUN chmod +x transformEnvironment.sh
RUN chmod +x copyJMXFiles.sh

