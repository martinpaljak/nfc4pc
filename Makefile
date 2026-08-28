TZ = UTC # same as Github
export TZ
SHELL = bash

default: today
	./mvnw

today:
	# for a dirty tree, set the date to today
	test -z "$(shell git status --porcelain)" || ./mvnw versions:set -DnewVersion=$(shell date +%y.%m.%d)-SNAPSHOT -DgenerateBackupPoms=false

conveyor:
	./mvnw clean package
	#conveyor make site --overwrite
