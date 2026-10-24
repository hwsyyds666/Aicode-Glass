# GitHub Workspaces

Open the workspace picker from the chat toolbar and choose **Add GitHub repository**. The first connection requires a GitHub OAuth App Client ID you control. Register an OAuth App under **Settings → Developer settings → OAuth Apps**, enable **Device Flow**, then enter its Client ID in AiCode. Device Flow does not use a client secret or a callback redirect. See GitHub's [OAuth App setup guide](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app).

Choose **Connect GitHub** to display a one-time code and open GitHub's verification page. After approval, AiCode detects the result and can browse repositories page by page, filter by name/description/owner, and clone the selected repository into a new local workspace. Empty repositories are supported. Disconnecting removes the authorization but does not delete repositories already cloned to the device.

The OAuth request uses GitHub's `repo` and `read:user` scopes. `repo` is broad: it grants read/write access to public and private repositories the account can access. Review the requested access before approving. The app does not request workflow or organization-administration scopes. Organization visibility may also depend on organization approval and SSO policy.

When you explicitly ask the agent to create a pull request, the app validates that the selected repository matches the workspace's `origin` and that `head` is the currently checked-out branch. Each pull request requires a separate user confirmation. The app creates the PR through GitHub's API but does not push the branch for you; the branch must already be available on GitHub. OAuth tokens stay in the app and are not included in model context.

Tokens are encrypted with Android Keystore-backed storage. For local container operations, the credential helper supplies the token only for the Git request and does not put it in the remote URL or conversation. In remote SSH mode, the local token is not forwarded to the server, so clone private repositories from the local container instead. If authorization expires or is revoked, reconnect; API rate limits show a suggested wait time.
