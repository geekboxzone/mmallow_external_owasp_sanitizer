package org.owasp.html;

import java.util.LinkedList;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

/**
 * Consumes an HTML stream, and dispatches events to a policy object which
 * decides which elements and attributes to allow.
 */
public final class HtmlSanitizer {

  /**
   * Receives events based on the HTML stream, and applies a policy to decide
   * what HTML constructs to allow.
   * Typically, implementations use an {@link HtmlStreamRenderer} to produce
   * the sanitized output.
   *
   * <p>
   * <b>Implementations of this class are in the TCB.</b>
   */
  @TCB
  interface Policy extends HtmlStreamEventReceiver {
    /**
     * Called when an HTML tag like {@code <foo bar=baz>} is seen in the input.
     *
     * @param elementName a normalized (lower-case for non-namespaced names)
     *     element name.
     * @param attrs a list of alternating attribute name and value pairs.
     *     For efficiency, this list may be mutated by this during this method
     *     call, but ownership reverts to the caller on method exit.
     *     The values are raw -- HTML entities have been decoded.
     *     Specifically, implementations are allowed to use a list iterator
     *     and remove all disallowed attributes, add necessary attributes, and
     *     then pass the list to an {@link HtmlStreamRenderer}.
     */
    void openTag(String elementName, List<String> attrs);

    /**
     * Called when an HTML tag like {@code </foo>} is seen in the input.
     *
     * @param elementName a normalized (lower-case for non-namespaced names)
     *     element name.
     */
    void closeTag(String elementName);

    /**
     * Called when textual content is seen.
     * @param textChunk raw content -- HTML entities have been decoded.
     */
    void text(String textChunk);
  }

  /**
   * Sanitizes the given HTML by applying the given policy to it.
   *
   * <p>
   * This method is not in the TCB.
   *
   * <p>
   * This method has no return value since policies are assumed to render things
   * they accept and do nothing on things they reject.
   * Use {@link HtmlStreamRenderer} to render content to an output buffer.
   *
   * @param html The html to sanitize.
   * @param policy The policy that should receive events based on the .
   *
   * @throws ParseException when the input HTML is too broken to sanitize.
   */
  public void sanitize(String html, final Policy policy) throws ParseException {
    HtmlStreamEventReceiver balancer = new TagBalancingHtmlStreamEventReceiver(
        policy);

    balancer.openDocument();

    HtmlLexer lexer = new HtmlLexer(html);
    // Use a linked list so that policies can use Iterator.remove() in an O(1)
    // way.
    LinkedList<String> attrs = Lists.newLinkedList();
    while (lexer.hasNext()) {
      HtmlToken token = lexer.next();
      switch (token.type) {
        case TEXT:
          balancer.text(decodeHtml(html.substring(token.start, token.end)));
          break;
        case UNESCAPED:
          balancer.text(html.substring(token.start, token.end));
          break;
        case TAGBEGIN:
          if (html.charAt(token.start + 1) == '/') {  // A close tag.
            balancer.closeTag(HtmlLexer.canonicalName(
                html.substring(token.start + 2, token.end)));
            while (lexer.hasNext()
                   && lexer.next().type != HtmlTokenType.TAGEND) {
              // skip tokens until we see a ">"
            }
          } else {
            attrs.clear();

            boolean attrsReadyForName = true;
            tagBody:
            while (lexer.hasNext()) {
              HtmlToken tagBodyToken = lexer.next();
              switch (tagBodyToken.type) {
                case ATTRNAME:
                  if (!attrsReadyForName) {
                    // Last attribute added was valueless.
                    attrs.add(attrs.getLast());
                  } else {
                    attrsReadyForName = false;
                  }
                  attrs.add(HtmlLexer.canonicalName(
                      html.substring(tagBodyToken.start, tagBodyToken.end)));
                  break;
                case ATTRVALUE:
                  attrs.add(decodeHtml(stripQuotes(
                      html.substring(tagBodyToken.start, tagBodyToken.end))));
                  attrsReadyForName = true;
                  break;
                case TAGEND:
                  break tagBody;
                default:
                  // Just drop anything not recognized
              }
            }
            if (!attrsReadyForName) {
              attrs.add(attrs.getLast());
            }
            balancer.openTag(
                HtmlLexer.canonicalName(
                    html.substring(token.start + 1, token.end)),
                attrs);
          }
          break;
        default:
          // Ignore comments, directives, and other stuff that shouldn't show
          // up in the output.
          break;
      }
    }

    balancer.closeDocument();
  }

  private String stripQuotes(String encodedAttributeValue) {
    int n = encodedAttributeValue.length();
    if (n > 0) {
      char last = encodedAttributeValue.charAt(n - 1);
      if (last == '"' || last == '\'') {
        int start = 0;
        if (n != 1 && last == encodedAttributeValue.charAt(0)) {
          start = 1;
        } else {
          // Browsers deal with missing left quotes : <img src=foo.png">
          // but generally do not deal with missing right : <img src="foo.png>
        }
        return encodedAttributeValue.substring(start, n - 1);
      }
    }
    return encodedAttributeValue;
  }

  @VisibleForTesting
  static String decodeHtml(String s) {
    int amp = s.indexOf('&');
    if (amp < 0) { return s; }
    int pos = 0;
    int n = s.length();
    StringBuilder sb = new StringBuilder(n);
    int end;
    do {
      long endAndCodepoint = HtmlEntities.decodeEntityAt(s, amp, n);
      end = (int) (endAndCodepoint >>> 32);
      int codepoint = (int) endAndCodepoint;
      sb.append(s, pos, amp).appendCodePoint(codepoint);
      pos = end;
    } while ((amp = s.indexOf('&', end)) >= 0);
    return sb.append(s, pos, n).toString();
  }

}
