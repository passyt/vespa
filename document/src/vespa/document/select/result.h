// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

/**
 * @class document::select::Result
 * @ingroup select
 *
 * @brief Represents a result of matching a document. Can be invalid.
 *
 * Using a bool to represent match or not proved inferior.
 * 'music.artist < 10' should not match any documents as long as music.artist
 * is a string field. However, we don't want 'not music.artist < 10' or
 * 'music.artist > 10' to match all documents because of that. This type is
 * thus used as it has 3 outcomes.. True, false & invalid.
 *
 * @author H�kon Humberset
 * @date 2007-20-05
 * @version $Id$
 */

#pragma once

#include <string>
#include <vespa/document/util/printable.h>

namespace document {
namespace select {

class Result : public Printable {
public:
    static Result Invalid;
    static Result False;
    static Result True;

    void print(std::ostream& out, bool verbose, const std::string& indent) const override;

    bool operator==(const Result& o) const { return (&o == this); }
    bool operator!=(const Result& o) const { return (&o != this); }

    const Result& operator&&(const Result&) const;
    const Result& operator||(const Result&) const;
    const Result& operator!() const;

    static const Result& get(bool b) { return (b ? True : False); }

    static uint32_t
    enumRange(void)
    {
        return 3u;
    }

    uint32_t
    toEnum(void) const
    {
        if (this == &Result::Invalid)
            return 0u;
        if (this == &Result::False)
            return 1u;
        if (this == &Result::True)
            return 2u;
        abort();
    }

    static const Result &
    fromEnum(uint32_t val)
    {
        if (val == 0u)
            return Result::Invalid;
        if (val == 1u)
            return Result::False;
        if (val == 2u)
            return Result::True;
        abort();
    }

private:
    Result();

        // Singletons are not copyable
    Result(const Result&);
    Result& operator=(const Result&);
};

} // select
} // document

