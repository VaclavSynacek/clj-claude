# clj-claude

A minimal wrapper around the Anthropic Claude API, intended to be used in scripts (babashka or small clojure programs).

## Design decision:
* minimal
* good for scripting:
  * no support for streaming, which just complicates matters in scripts (as opposed to interactive tools where streaming is essential)
  * intentionally relies on [babashka.curl](https://github.com/babashka/babashka.curl) rather than http clients for long-lived processes
* should support calling/billing through:
  * direct call to [Anthropic API](https://docs.anthropic.com/en/api/getting-started)
  * call through [AWS Bedrock](https://aws.amazon.com/bedrock/) (not yet implemented)
  * maybe others
