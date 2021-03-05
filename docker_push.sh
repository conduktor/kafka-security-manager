#!/bin/bash

./docker_build.sh

docker_push(){
    echo "Pushing tag $1 to docker hub"
    docker tag "conduktor/kafka-security-manager:latest" "conduktor/kafka-security-manager:$1"
    docker push "conduktor/kafka-security-manager:$1"
}

if [[ -z "$GITHUB_BASE_REF" ]]; then
    if [[ "$RELEASE_VERSION" == v*  ]]; then
        # Tagging should trigger a release in Docker Hub, that's immutable.
        echo "git tag action, push the tag"
        docker_push "$RELEASE_VERSION-release"
    elif [[ "$RELEASE_VERSION" == "master" ]]; then
        # we push to latest when master is built and that's not a pull request
        echo "master build, push to latest"
        docker_push "latest"
    fi;
fi;