# Tests for PUT /api/articles/:slug/comments/:id (not part of the official RealWorld suite).

# Update comment without auth
PUT {{host}}/api/articles/some-slug/comments/1
{
  "comment": {
    "body": "new"
  }
}
HTTP 401
[Asserts]
jsonpath "$.errors.token[0]" == "is missing"

# Setup: register the comment author
POST {{host}}/api/users
{
  "user": {
    "username": "uc_{{uid}}",
    "email": "uc_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
token: jsonpath "$.user.token"

# Setup: register another user
POST {{host}}/api/users
{
  "user": {
    "username": "uc2_{{uid}}",
    "email": "uc2_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
token2: jsonpath "$.user.token"

# Setup: create an article
POST {{host}}/api/articles
Authorization: Token {{token}}
{
  "article": {
    "title": "Update Comment Art {{uid}}",
    "description": "test",
    "body": "test"
  }
}
HTTP 201
[Captures]
slug: jsonpath "$.article.slug"

# Setup: create a comment
POST {{host}}/api/articles/{{slug}}/comments
Authorization: Token {{token}}
{
  "comment": {
    "body": "original body"
  }
}
HTTP 201
[Captures]
comment_id: jsonpath "$.comment.id"
created_at: jsonpath "$.comment.createdAt"
updated_at: jsonpath "$.comment.updatedAt"

# Update comment as author
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token}}
[Options]
delay: 20
{
  "comment": {
    "body": "updated body"
  }
}
HTTP 200
[Asserts]
jsonpath "$.comment.id" == {{comment_id}}
jsonpath "$.comment.body" == "updated body"
jsonpath "$.comment.createdAt" == "{{created_at}}"
jsonpath "$.comment.updatedAt" != "{{updated_at}}"
jsonpath "$.comment.updatedAt" isIsoDate
jsonpath "$.comment.author.username" == "uc_{{uid}}"
jsonpath "$.comment.author.following" == false
jsonpath "$.comment.author.bio" == null
jsonpath "$.comment.author.image" == null

# List comments returns the new body
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments" count == 1
jsonpath "$.comments[0].id" == {{comment_id}}
jsonpath "$.comments[0].body" == "updated body"
jsonpath "$.comments[0].createdAt" == "{{created_at}}"

# Update comment as another user
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token2}}
{
  "comment": {
    "body": "hijacked"
  }
}
HTTP 403
[Asserts]
jsonpath "$.errors.comment[0]" == "forbidden"

# Update comment with empty body
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token}}
{
  "comment": {
    "body": ""
  }
}
HTTP 422
[Asserts]
jsonpath "$.errors.body[0]" == "can't be blank"

# Update unknown comment id
PUT {{host}}/api/articles/{{slug}}/comments/99999999
Authorization: Token {{token}}
{
  "comment": {
    "body": "new"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.comment[0]" == "not found"

# Update comment on unknown article
PUT {{host}}/api/articles/unknown-slug-{{uid}}/comments/{{comment_id}}
Authorization: Token {{token}}
{
  "comment": {
    "body": "new"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.article[0]" == "not found"

# Body unchanged after the failed updates
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments[0].body" == "updated body"

# Cleanup
DELETE {{host}}/api/articles/{{slug}}
Authorization: Token {{token}}
HTTP 204
