/* NeoChat core C ABI. All strings are UTF-8 JSON. */
#ifndef NEOCORE_H
#define NEOCORE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Events include a local account_id. account_selected changes the displayed account;
 * account_notify belongs to another account and must only enter the notification path. */
typedef void (*nc_event_fn)(void *ctx, const char *json);

/* config: {"data_dir": "...", "db_key": "<64 hex chars>", "foreground": true}
 * Returns NULL on failure and stores an error JSON in *error_out (free with nc_free). */
void *nc_open(const char *config_json, nc_event_fn cb, void *ctx, char **error_out);

/* Returns {"ok": <result>} or {"error": {"code": "...", "message": "..."}}.
 * list_accounts, add_account and switch_account ({account_id}) manage local sessions.
 * Bind queued calls with _account_id; discard UI results after account selection changes.
 * May block on the network: never call from the UI thread. Free with nc_free. */
char *nc_call(void *core, const char *method, const char *params_json);

void nc_free(char *s);
void nc_close(void *core);

#ifdef __cplusplus
}
#endif

#endif
