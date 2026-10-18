# Tests for PUT /api/articles/:slug/comments/:id (update a comment).

# Update comment without a token
PUT {{host}}/api/articles/some-slug/comments/1
{
  "comment": {
    "body": "test"
  }
}
HTTP 401
[Asserts]
jsonpath "$.errors.token[0]" == "is missing"

# Setup: register the comment author
POST {{host}}/api/users
{
  "user": {
    "username": "cu_author_{{uid}}",
    "email": "cu_author_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
author_token: jsonpath "$.user.token"

# Setup: register another user
POST {{host}}/api/users
{
  "user": {
    "username": "cu_other_{{uid}}",
    "email": "cu_other_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
other_token: jsonpath "$.user.token"

# Setup: create an article
POST {{host}}/api/articles
Authorization: Token {{author_token}}
{
  "article": {
    "title": "Comment Update Art {{uid}}",
    "description": "test",
    "body": "test"
  }
}
HTTP 201
[Captures]
slug: jsonpath "$.article.slug"

# Setup: create a comment
POST {{host}}/api/articles/{{slug}}/comments
Authorization: Token {{author_token}}
{
  "comment": {
    "body": "Original body"
  }
}
HTTP 201
[Captures]
comment_id: jsonpath "$.comment.id"
created_at: jsonpath "$.comment.createdAt"
updated_at: jsonpath "$.comment.updatedAt"

# Update comment as the author (delay so updatedAt can change)
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{author_token}}
[Options]
delay: 20
{
  "comment": {
    "body": "Updated body"
  }
}
HTTP 200
[Asserts]
header "Content-Type" contains "application/json"
jsonpath "$.comment.id" == {{comment_id}}
jsonpath "$.comment.body" == "Updated body"
jsonpath "$.comment.createdAt" == "{{created_at}}"
jsonpath "$.comment.updatedAt" isIsoDate
jsonpath "$.comment.updatedAt" != "{{updated_at}}"
jsonpath "$.comment.author.username" == "cu_author_{{uid}}"
jsonpath "$.comment.author.following" == false
jsonpath "$.comment.author.bio" == null
jsonpath "$.comment.author.image" == null

# The comment list returns the new body
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments" count == 1
jsonpath "$.comments[0].id" == {{comment_id}}
jsonpath "$.comments[0].body" == "Updated body"
jsonpath "$.comments[0].createdAt" == "{{created_at}}"

# Update comment as another user
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{other_token}}
{
  "comment": {
    "body": "Hijacked"
  }
}
HTTP 403
[Asserts]
jsonpath "$.errors.comment[0]" == "forbidden"

# Update comment with an empty body
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{author_token}}
{
  "comment": {
    "body": ""
  }
}
HTTP 422
[Asserts]
jsonpath "$.errors.body[0]" == "can't be blank"

# Update an unknown comment on an existing article
PUT {{host}}/api/articles/{{slug}}/comments/99999999
Authorization: Token {{author_token}}
{
  "comment": {
    "body": "test"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.comment[0]" == "not found"

# Update a comment on an unknown article
PUT {{host}}/api/articles/unknown-slug-{{uid}}/comments/{{comment_id}}
Authorization: Token {{author_token}}
{
  "comment": {
    "body": "test"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.article[0]" == "not found"

# Rejected updates did not change the comment
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments[0].body" == "Updated body"

# Cleanup
DELETE {{host}}/api/articles/{{slug}}
Authorization: Token {{author_token}}
HTTP 204
