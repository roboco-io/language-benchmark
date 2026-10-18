#!/bin/sh
# Creates or updates the database schema, then starts the clustered server.
set -e
./node_modules/.bin/prisma migrate deploy --schema prisma/schema.prisma
exec node dist/index.js
