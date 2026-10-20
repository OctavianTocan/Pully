---
name: read-pully-feedback
description: Read the feedback the person sent from the Pully app, act on it, and mark each note done. Use when someone asks you to go through, act on or fix their Pully feedback.
---

# Read Pully feedback

The person sends feedback from the Pully app on their phone (Commands or Settings, Send
feedback). Each note is kept on their Pully server with when it came, the app version it
came from, the screen and the pull request it was written on, the phone and its Android
version, and the theme. You read it with an account key, act on it, and mark it done so the
next agent does not do it again. The app shows Done next to notes you marked.

## What you need

- `PULLY_API_KEY`: a key that starts with `pully_`. The person makes one in the Pully app,
  Settings, Agent keys, and gives it to you. Never print it, commit it or put it in a file.
- `PULLY_URL`: the server, such as `https://prs.octaviantocan.com`. Ask for it if you do not
  have it.

Every call sends `Authorization: Bearer $PULLY_API_KEY`. Without a valid key every call
answers 401. A key reads only its own account's feedback.

## Read it

```sh
curl -sS "$PULLY_URL/api/v1/feedback?status=open" -H "Authorization: Bearer $PULLY_API_KEY"
```

It answers the notes newest first. Leave out `?status=open` for every note, or use
`?status=done` for the handled ones:

```json
{
  "feedback": [
    {
      "id": "a1B2c3D4e5F6",
      "text": "The Inbox chip jumps when the list loads.",
      "createdAt": "2026-10-20T06:10:45.000Z",
      "status": "open",
      "app": { "version": "0.51.0", "versionCode": 65 },
      "screen": "home",
      "place": "octaviantocan/pulls.review/70",
      "device": { "manufacturer": "Google", "model": "Pixel 8", "android": "16", "sdk": 36 },
      "theme": "dark",
      "locale": "en"
    }
  ]
}
```

- `createdAt` is the server's time. `sentAt`, when present, is the phone's clock.
- `screen` is the app's name for the page: `home` (the Inbox), `repo`, `review` (a pull
  request), `settings` or `settings-<page>`, `uibook`.
- `place` is the pull request (`owner/repo/number`, or a compare or branch target) or the
  repository (`owner/repo`) on that page, when there was one.
- `app.version` tells you which build the person was looking at. Check whether a later
  version already changed what the note is about before you change it again.

One note on its own: `GET $PULLY_URL/api/v1/feedback/<id>`.

## Mark it done

Once the change is made (a pull request is open or merged), mark the note done with a short
note on what was done:

```sh
curl -sS -X PATCH "$PULLY_URL/api/v1/feedback/a1B2c3D4e5F6" \
  -H "Authorization: Bearer $PULLY_API_KEY" -H "Content-Type: application/json" \
  -d '{"status":"done","note":"Fixed in PR 70 (0.52.0)"}'
```

It answers the note with `status`, `doneAt` and `note`. `{"status":"open"}` opens it again
and clears the note. Mark a note done only when it is handled, not when you have only read
it. The note can be at most 2000 characters.

## Errors

Every error is `{ "error": "..." }` with a status: 401 without a valid key, 404 for an id
that is not the account's, 400 for a bad `status`.
