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
    "username": "cu_{{uid}}",
    "email": "cu_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
token: jsonpath "$.user.token"

# Setup: register a second user
POST {{host}}/api/users
{
  "user": {
    "username": "cu2_{{uid}}",
    "email": "cu2_{{uid}}@test.com",
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

# Update the comment as its author
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token}}
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
jsonpath "$.comment.updatedAt" isString
jsonpath "$.comment.updatedAt" != "{{updated_at}}"
jsonpath "$.comment.updatedAt" matches /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/
jsonpath "$.comment.author.username" == "cu_{{uid}}"
jsonpath "$.comment.author.following" == false
jsonpath "$.comment.author.bio" == null
jsonpath "$.comment.author.image" == null

# The list returns the new body
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments" count == 1
jsonpath "$.comments[0].id" == {{comment_id}}
jsonpath "$.comments[0].body" == "updated body"
jsonpath "$.comments[0].createdAt" == "{{created_at}}"

# Update the comment as another user
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

# Update with an empty body
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

# Update an unknown comment id
PUT {{host}}/api/articles/{{slug}}/comments/999999999
Authorization: Token {{token}}
{
  "comment": {
    "body": "nothing"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.comment[0]" == "not found"

# Update a comment on an unknown article
PUT {{host}}/api/articles/unknown-slug-{{uid}}/comments/{{comment_id}}
Authorization: Token {{token}}
{
  "comment": {
    "body": "nothing"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.article[0]" == "not found"

# The failed updates did not change the comment
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments[0].body" == "updated body"

# Cleanup
DELETE {{host}}/api/articles/{{slug}}
Authorization: Token {{token}}
HTTP 204
