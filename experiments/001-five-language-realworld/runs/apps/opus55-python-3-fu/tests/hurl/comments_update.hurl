# Tests for PUT /api/articles/:slug/comments/:id (update a comment).

# Setup: Register the comment author
POST {{host}}/api/users
{
  "user": {
    "username": "cmtupd_{{uid}}",
    "email": "cmtupd_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
token: jsonpath "$.user.token"

# Setup: Register another user
POST {{host}}/api/users
{
  "user": {
    "username": "cmtupd_other_{{uid}}",
    "email": "cmtupd_other_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
other_token: jsonpath "$.user.token"

# Setup: Create article
POST {{host}}/api/articles
Authorization: Token {{token}}
{
  "article": {
    "title": "Comment Update Article {{uid}}",
    "description": "For comment updates",
    "body": "Article body"
  }
}
HTTP 201
[Captures]
slug: jsonpath "$.article.slug"

# Setup: Create comment
POST {{host}}/api/articles/{{slug}}/comments
Authorization: Token {{token}}
{
  "comment": {
    "body": "Original comment body"
  }
}
HTTP 201
[Captures]
comment_id: jsonpath "$.comment.id"
created_at: jsonpath "$.comment.createdAt"
updated_at: jsonpath "$.comment.updatedAt"

# Update comment as the author (delay so updatedAt visibly changes)
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token}}
[Options]
delay: 50
{
  "comment": {
    "body": "Updated comment body"
  }
}
HTTP 200
[Asserts]
jsonpath "$.comment.id" == {{comment_id}}
jsonpath "$.comment.body" == "Updated comment body"
jsonpath "$.comment.createdAt" == "{{created_at}}"
jsonpath "$.comment.updatedAt" matches "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"
jsonpath "$.comment.updatedAt" != "{{updated_at}}"
jsonpath "$.comment.author.username" == "cmtupd_{{uid}}"
jsonpath "$.comment.author.following" == false

# List comments reflects the new body
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments" count == 1
jsonpath "$.comments[0].id" == {{comment_id}}
jsonpath "$.comments[0].body" == "Updated comment body"
jsonpath "$.comments[0].createdAt" == "{{created_at}}"

# Update by another user is forbidden
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

# Update without a token
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
{
  "comment": {
    "body": "No auth"
  }
}
HTTP 401
[Asserts]
jsonpath "$.errors.token[0]" == "is missing"

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

# Update a non-existent comment
PUT {{host}}/api/articles/{{slug}}/comments/999999999
Authorization: Token {{token}}
{
  "comment": {
    "body": "Ghost"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.comment[0]" == "not found"

# Update a comment on a non-existent article
PUT {{host}}/api/articles/no-such-article-{{uid}}/comments/{{comment_id}}
Authorization: Token {{token}}
{
  "comment": {
    "body": "Ghost"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.article[0]" == "not found"

# Rejected updates left the comment unchanged
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments[0].body" == "Updated comment body"

# Cleanup: Delete comment
DELETE {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token}}
HTTP 204
