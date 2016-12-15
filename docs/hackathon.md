This document will be sparse. I hope to convery information verbally, either as a presentation to the group or one-on-one as you work. But this is here as a reference.

* [Useful Info](#useful-info)
    * [SSH to the VM](#ssh-to-the-vm)
* [Overview](#overview)
* [Docker](#docker)
    * [Making a docker image](#making-a-docker-image)
    * [Running a container](#running-a-container)
        * [Mounting data](#mounting-data)
    * [Getting your image onto the server](#getting-your-image-onto-the-server)
* [Container Service](#container-service)

# Useful Info

* Hackathon VM: [https://containers-demo.xnat.org](https://containers-demo.xnat.org)
* Swagger UI [https://containers-demo.xnat.org/xapi/swagger-ui.html](https://containers-demo.xnat.org/xapi/swagger-ui.html)
* Private key file for SSH: [ftp://ftp.nrg.wustl.edu/pub/data/containers/hackathon/keys/hackathon](ftp://ftp.nrg.wustl.edu/pub/data/containers/hackathon/keys/hackathon)
* Example Dockerfiles / Commands: [https://github.com/NrgXnat/docker-images](https://github.com/NrgXnat/docker-images)

## SSH to the VM
Get the private key file from the [FTP link](ftp://ftp.nrg.wustl.edu/pub/data/containers/hackathon/keys/hackathon). Put it somewhere (`~/.ssh` is a good place for linux/macOS).

SSH to the VM with the following info:

* username: `hack`
* server: `containers-demo.xnat.org`
* Private key passphrase: `gruesome-demigod` (It was randomly generated.)

So your ssh line will be:

    $ ssh -i <path to private key> hack@containers-demo.xnat.org
    Enter passphrase for key '<path to private key>':

And you will enter the passphrase `gruesome-demigod`.

# Overview
Today we will be

* Learning about docker
* Learning about the XNAT Container Service
* Making a docker image
* Writing a Command
* Launching a container from your Command

There are two things you need to have to run something with the container service:

1. A docker image
2. A Command that describes that docker image



# Docker

Docker is an executable that you can use to build and run containers. As part of this hackathon, you should create a docker image to do something that you want to do from within XNAT.

## Making a Docker image
Write a Dockerfile. [tutorial](https://docs.docker.com/engine/getstarted/step_four/)

You can check some examples that I wrote: [https://github.com/NrgXnat/docker-images](https://github.com/NrgXnat/docker-images).

## Running a container

Your Docker image is a perfect, unchanging, etherial entity. It only has some executable and the bare minimum of requirements. But you probably want to actually _do_ something with it. You do that by launching a container from the image.

    $ docker run <name of your image> <the command line for your image>

### Mounting data
It is likely that we will have some data outside the container that we want to bring inside (say, some dicom files that we want to do something with). To do that, we must **mount** the data into the container.

    $ docker run -v /path/to/data:/path/inside <name of your image> <the command line for your image>

Whatever is in the directory `/path/to/data` on the host machine will be seen by your container at `/path/inside`.

So, for example, let's say you are running a dicom to nifti conversion. On your host machine you have a directory full of dicom files at `/data/my_project/subject_123/T1/resources/dicom` and you've created an empty destination directory at `/temp/results/subject_123`. Your docker image (and the containers that spawn from it) could be written to expect to read files at `/input` and write files to `/output`.

You could mount those directories into your container as `/input` and `/output`, or `/dicom` and `/nifti`, or whatever you want.

## Getting your image onto the server
If you build it on your local machine, and push it up to Docker Hub, you can get it onto the server by

* SSHing in to the VM and building the image there
* Push your image up to Docker Hub, and
    * Pull it down on the VM using docker
    * Pull it down on the VM using XNAT by `POST`ing to `/xapi/docker/images/pull?image=<your image name>`

# Container Service

The Container Service is an XNAT 1.7 plugin that allows XNAT and its users to launch docker containers to do work on their XNAT data.

## Command
The all-important object that you need to create to run a docker image with the container service is the Command. (I wrote a lot about the format of the Command [here](https://wiki.xnat.org/display/CS/Command).)

You will need to define a Command to run your docker image. You will put into this Command definition things like:

* The docker image you're running
* How to run the image on the command line
* What data needs to be mounted? (This data will likely come from something in XNAT, so you'll need...)
* Inputs? Simple things like strings and booleans, or XNAT things like Sessions, Scans, and Resources.
* Outputs? If you want files to go back into XNAT, you'll need to note where they are, and where they will go.

See the [Command documentation](https://wiki.xnat.org/display/CS/Command) for more.



