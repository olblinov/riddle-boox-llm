# Storage plan

Keep backups for **30 days**. This draft needs review before implementation.

## Decisions

- Keep one primary database.
- Store backups separately.
- Restore tests run every week.

| Setting | Proposed value |
| --- | --- |
| Backup retention | 30 days |
| Restore test | Weekly |

## Configuration

```yaml
backup:
  retentionDays: 30
  restoreTest: weekly
```

> Review question: is the retention period appropriate?

Leave pen edits beside the relevant paragraph, table row, or configuration line, then tap Send.
