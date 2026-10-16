use super::*;

const STACK_FIELDS:&str="number title state isDraft merged headRefName baseRefName isCrossRepository additions deletions commits(last:1){nodes{commit{statusCheckRollup{state}}}}";
fn stack_entry(raw: &Value, position: u64, current: bool) -> Value {
    let mut out = json!({"number":raw["number"],"title":raw["title"],"state":pr_state(raw),"position":position,"current":current,"headRef":raw["headRefName"],"baseRef":raw["baseRefName"],"additions":raw["additions"],"deletions":raw["deletions"]});
    if raw["commits"]["nodes"].is_array() {
        let checks = rollup(&raw["commits"]["nodes"][0]["commit"]["statusCheckRollup"]["state"]);
        out["checks"] = if checks.is_null() {
            json!("none")
        } else {
            checks
        };
    }
    omit_nulls(&mut out);
    out
}
fn ordered_stack(entries: &mut [Value]) {
    entries.sort_by_key(|e| {
        (
            number(&e["position"]).unwrap_or(0),
            number(&e["number"]).unwrap_or(0),
        )
    });
}
pub(super) fn native_stack(raw: &Value, current: u64) -> Option<Value> {
    if raw.is_null() {
        return None;
    }
    let mut entries: Vec<Value> = list(&raw["entries"]["nodes"])
        .iter()
        .filter(|n| n["pullRequest"].is_object())
        .map(|n| {
            stack_entry(
                &n["pullRequest"],
                number(&n["position"]).unwrap_or(1),
                number(&n["pullRequest"]["number"]) == Some(current),
            )
        })
        .collect();
    if entries.len() < 2 {
        return None;
    }
    ordered_stack(&mut entries);
    Some(json!({"native":true,"number":raw["number"],"base":raw["baseRefName"],"entries":entries}))
}
async fn open_stack_pulls(
    state: &AppState,
    pull: &Pull,
    branch: &str,
    below: bool,
) -> Result<Vec<Value>> {
    let field = if below { "headRefName" } else { "baseRefName" };
    let query=format!("query StackRelated($owner:String!,$repo:String!,$ref:String!){{repository(owner:$owner,name:$repo){{pullRequests({field}:$ref,states:OPEN,first:5){{nodes{{{STACK_FIELDS}}}}}}}}}");
    let response = graphql(
        state,
        &query,
        json!({"owner":pull.owner,"repo":pull.repo,"ref":branch}),
    )
    .await?;
    Ok(list(&response["repository"]["pullRequests"]["nodes"])
        .iter()
        .filter(|p| p.is_object() && !bool_at(p, "isCrossRepository"))
        .cloned()
        .collect())
}
pub(super) async fn stack(state: &AppState, args: &Value) -> Result<Value> {
    let pull = Pull::from(args)?;
    let native_query = r#"query PullRequestStack($owner:String!,$repo:String!,$number:Int!) {
      repository(owner:$owner,name:$repo) {
        defaultBranchRef { name }
        pullRequest(number:$number) {
          $FIELDS
          stack { number baseRefName entries(first:50) {
            nodes { position pullRequest { $FIELDS } }
          } }
        }
      }
    }"#
    .replace("$FIELDS", STACK_FIELDS);
    let raw = match graphql(state, &native_query, pull.variables()).await {
        Ok(data) => data,
        Err(_) => {
            let query=format!("query StackPull($owner:String!,$repo:String!,$number:Int!){{repository(owner:$owner,name:$repo){{defaultBranchRef{{name}} pullRequest(number:$number){{{STACK_FIELDS}}}}}}}");
            graphql(state, &query, pull.variables()).await?
        }
    };
    if let Some(native) = native_stack(&raw["repository"]["pullRequest"]["stack"], pull.number) {
        return Ok(json!({"stack":native}));
    }
    let current = &raw["repository"]["pullRequest"];
    if !current.is_object() {
        bail!("Pull request not found.")
    }
    let default_branch = text(&raw["repository"]["defaultBranchRef"], "name");
    let mut seen = HashSet::from([pull.number]);
    let mut below = Vec::new();
    let mut cursor = current.clone();
    for _ in 0..10 {
        let branch = text(&cursor, "baseRefName");
        if branch.is_empty() || branch == default_branch {
            break;
        }
        let under = open_stack_pulls(state, &pull, branch, true).await?;
        let next = under
            .iter()
            .find(|p| number(&p["number"]).is_some_and(|n| !seen.contains(&n)));
        if let Some(next) = next {
            seen.insert(positive(next, "number")?);
            below.insert(0, next.clone());
            cursor = next.clone();
        } else {
            if !under.is_empty() {
                return Ok(json!({"stack":null}));
            }
            break;
        }
    }
    let mut chain = below.clone();
    chain.push(current.clone());
    let own = chain.len();
    let mut above: Vec<(Value, u64)> = Vec::new();
    let mut level = vec![chain[0].clone()];
    for position in 2..=(own + 10) {
        if level.is_empty() {
            break;
        }
        let mut fresh = Vec::new();
        for base in &level {
            if bool_at(base, "isCrossRepository") || text(base, "headRefName") == default_branch {
                continue;
            }
            for candidate in
                open_stack_pulls(state, &pull, text(base, "headRefName"), false).await?
            {
                if seen.insert(positive(&candidate, "number")?) {
                    fresh.push(candidate);
                }
            }
        }
        above.extend(fresh.iter().cloned().map(|p| (p, position as u64)));
        level = if position <= own {
            let mut level = vec![chain[position - 1].clone()];
            level.extend(fresh);
            level
        } else {
            fresh
        };
    }
    if below.is_empty() && above.is_empty() {
        return Ok(json!({"stack":null}));
    }
    let mut entries: Vec<Value> = below
        .iter()
        .enumerate()
        .map(|(i, p)| stack_entry(p, (i + 1) as u64, false))
        .collect();
    entries.push(stack_entry(current, own as u64, true));
    entries.extend(above.iter().map(|(p, pos)| stack_entry(p, *pos, false)));
    ordered_stack(&mut entries);
    Ok(json!({"stack":{"native":false,"base":chain[0]["baseRefName"],"entries":entries}}))
}
