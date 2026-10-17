# Setup: Register user A
POST {{host}}/api/users
{
  "user": {
    "username": "cupd_a_{{uid}}",
    "email": "cupd_a_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
token_a: jsonpath "$.user.token"

# Setup: Register user B
POST {{host}}/api/users
{
  "user": {
    "username": "cupd_b_{{uid}}",
    "email": "cupd_b_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
token_b: jsonpath "$.user.token"

# Setup: A creates article
POST {{host}}/api/articles
Authorization: Token {{token_a}}
{
  "article": {
    "title": "Comment Update Article {{uid}}",
    "description": "For comment update",
    "body": "Article body"
  }
}
HTTP 201
[Captures]
slug: jsonpath "$.article.slug"

# Setup: A creates comment
POST {{host}}/api/articles/{{slug}}/comments
Authorization: Token {{token_a}}
{
  "comment": {
    "body": "Original body"
  }
}
HTTP 201
[Captures]
comment_id: jsonpath "$.comment.id"

# Update comment as author
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token_a}}
{
  "comment": {
    "body": "Edited body"
  }
}
HTTP 200
[Asserts]
jsonpath "$.comment.id" == {{comment_id}}
jsonpath "$.comment.body" == "Edited body"
jsonpath "$.comment.createdAt" matches "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"
jsonpath "$.comment.updatedAt" matches "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"
jsonpath "$.comment.author.username" == "cupd_a_{{uid}}"

# Verify the edit in the comment list
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments" count == 1
jsonpath "$.comments[0].id" == {{comment_id}}
jsonpath "$.comments[0].body" == "Edited body"

# Update comment as non-author
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token_b}}
{
  "comment": {
    "body": "Hijacked body"
  }
}
HTTP 403
[Asserts]
jsonpath "$.errors.comment[0]" == "forbidden"

# Update comment no auth
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
{
  "comment": {
    "body": "Anonymous body"
  }
}
HTTP 401
[Asserts]
jsonpath "$.errors.token[0]" == "is missing"

# Update comment empty body
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token_a}}
{
  "comment": {
    "body": ""
  }
}
HTTP 422
[Asserts]
jsonpath "$.errors.body[0]" == "can't be blank"

# Update non-existent comment on existing article
PUT {{host}}/api/articles/{{slug}}/comments/999999999
Authorization: Token {{token_a}}
{
  "comment": {
    "body": "Edited body"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.comment[0]" == "not found"

# Update comment on unknown article
PUT {{host}}/api/articles/unknown-slug-{{uid}}/comments/{{comment_id}}
Authorization: Token {{token_a}}
{
  "comment": {
    "body": "Edited body"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.article[0]" == "not found"

# Verify the failed updates left the edited body unchanged
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments" count == 1
jsonpath "$.comments[0].body" == "Edited body"

# Cleanup
DELETE {{host}}/api/articles/{{slug}}
Authorization: Token {{token_a}}
HTTP 204
