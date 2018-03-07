#!/bin/bash

./docker_build.sh

docker_push(){
    echo "Pushing tag $1 to docker hub"
    docker tag "simplesteph/kafka-security-manager:latest" "simplesteph/kafka-security-manager:$1"
    docker login -u "$DOCKER_USERNAME" -p "$DOCKER_PASSWORD";
    docker push "simplesteph/kafka-security-manager:$1"
}

# TRAVIS_TAG: If the current build is for a git tag, this variable is set to the tag’s name.
# TRAVIS_PULL_REQUEST_BRANCH: if the current job is a pull request, the name of the branch from which the PR originated.

if [[ "$TRAVIS_PULL_REQUEST_BRANCH" == "" ]]; then
    if [[ "$TRAVIS_TAG" =! "" ]]; then
        # Tagging should trigger a release in Docker Hub, that's immutable.
        echo "git tag action, push the tag"
        docker_push "$TRAVIS_TAG-release"
    elif [[ "$TRAVIS_BRANCH" == "master" ]]; then
        # we push to latest when master is built and that's not a pull request
        echo "master build, push to latest"
        docker_push "latest"
    elif [[ "$TRAVIS_BRANCH" ~= "v*" ]]; then
        # this is a version branch and we push it.
        echo "branch build, push to branch-latest"
        docker_push "$TRAVIS_BRANCH-latest"
    fi;
fi;