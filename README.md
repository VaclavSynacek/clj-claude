# clj-claude

A minimal wrapper around the Anthropic Claude APIs, intended to be used in scripts (babashka or small clojure programs).

## Design decision:
* minimal
* good for scripting:
  * no support for streaming, which just complicates matters in scripts (as opposed to interactive tools where streaming is essential)
  * intentionally relies on [babashka.curl](https://github.com/babashka/babashka.curl) rather than http clients for long-lived processes
* should support calling/billing through several providers

## Feature status
- [ ] ~~streaming response~~
- [x] call through [Anthropic API](https://docs.anthropic.com/en/api/getting-started)
- [ ] call through [AWS Bedrock API](https://aws.amazon.com/bedrock/)
- [ ] [Vision](https://docs.anthropic.com/en/docs/build-with-claude/vision)
- [ ] [Tool use](https://docs.anthropic.com/en/docs/build-with-claude/tool-use)
- [ ] [Computer use (beta)](https://docs.anthropic.com/en/docs/build-with-claude/computer-use)
- [X] [Prompt caching (beta)](https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching)
- [ ] [Message batches (beta)](https://docs.anthropic.com/en/docs/build-with-claude/message-batches)
- [X] [Token counting (beta)](https://docs.anthropic.com/en/docs/build-with-claude/token-counting)

## Usage
See `(comment` block at the end of [clj-claude.scripting](https://github.com/VaclavSynacek/clj-claude/blob/7a38a32b9bae98e5b6aa09cfa2de7b00f112d2ab/src/clj_claude/scripting.clj#L85)
