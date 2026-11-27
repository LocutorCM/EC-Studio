#include "MainComponent.h"
#include <cmath>

namespace
{
const juce::Colour bg(0xff101319), surface(0xff181d25), panel(0xff1d242e), panel2(0xff252e3a);
const juce::Colour border(0xff303b49), mutedText(0xff8996a7), white(0xffe8edf5);
const juce::Colour accent(0xff38a3ff), accent2(0xff63c5ff), green(0xff54d6a0), red(0xffff5968);
const juce::Colour palette[] = { juce::Colour(0xff399fe5), juce::Colour(0xffa17bea), juce::Colour(0xffe4a34d),
                                  juce::Colour(0xff4bc6a5), juce::Colour(0xffe2769a), juce::Colour(0xff7d9df5) };

void styleButton(juce::Button& b, juce::Colour colour, bool toggle = false)
{
    b.setColour(juce::TextButton::buttonColourId, colour);
    b.setColour(juce::TextButton::buttonOnColourId, colour.brighter(0.22f));
    b.setColour(juce::TextButton::textColourOffId, white);
    b.setColour(juce::TextButton::textColourOnId, white);
    b.setMouseCursor(juce::MouseCursor::PointingHandCursor);
    b.setClickingTogglesState(toggle);
}
}

MainComponent::MainComponent()
{
    setSize(1500, 940);
    setAudioChannels(2, 2);
    addTrack();
    tracks.front()->name = "Audio 1";

    styleButton(recordButton, red);
    styleButton(playButton, accent);
    styleButton(stopButton, panel2);
    styleButton(newTrackButton, accent);
    styleButton(importButton, panel2);
    styleButton(exportButton, green);
    styleButton(mixerButton, panel2);
    for (auto* b : { static_cast<juce::Button*>(&loopButton), static_cast<juce::Button*>(&clickButton) })
        styleButton(*b, panel2, true);
    for (auto* b : { static_cast<juce::Button*>(&muteButton), static_cast<juce::Button*>(&soloButton), static_cast<juce::Button*>(&armButton) })
        styleButton(*b, panel2, true);

    recordButton.onClick = [this] { startRecording(); };
    playButton.onClick = [this] { startPlayback(); };
    stopButton.onClick = [this] { stopTransport(); };
    newTrackButton.onClick = [this] { addTrack(); };
    importButton.onClick = [this] { importAudio(); };
    exportButton.onClick = [this] { exportMix(); };
    mixerButton.onClick = [this] { mixerVisible = !mixerVisible; resized(); repaint(); };
    loopButton.onClick = [this] { loopEnabled.store(loopButton.getToggleState()); };
    clickButton.onClick = [this] { clickEnabled.store(clickButton.getToggleState()); };

    muteButton.onClick = [this] { if (auto* t = trackSlots[static_cast<size_t>(selectedTrack.load())].load()) t->muted.store(muteButton.getToggleState()); };
    soloButton.onClick = [this] { if (auto* t = trackSlots[static_cast<size_t>(selectedTrack.load())].load()) t->solo.store(soloButton.getToggleState()); };
    armButton.onClick = [this] { if (auto* t = trackSlots[static_cast<size_t>(selectedTrack.load())].load()) t->armed.store(armButton.getToggleState()); };

    const std::array<juce::Component*, 12> controls = { &recordButton, &playButton, &stopButton, &newTrackButton,
        &importButton, &exportButton, &mixerButton, &loopButton, &clickButton, &muteButton, &soloButton, &armButton };
    for (auto* c : controls) addAndMakeVisible(c);

    auto setupSlider = [](juce::Slider& s, double min, double max, double value, juce::Slider::SliderStyle style)
    {
        s.setSliderStyle(style);
        s.setTextBoxStyle(juce::Slider::TextBoxRight, false, 54, 20);
        s.setRange(min, max, 0.01);
        s.setValue(value, juce::dontSendNotification);
        s.setColour(juce::Slider::trackColourId, accent);
        s.setColour(juce::Slider::thumbColourId, accent2);
        s.setColour(juce::Slider::textBoxTextColourId, white);
        s.setColour(juce::Slider::textBoxBackgroundColourId, panel2);
        s.setColour(juce::Slider::textBoxOutlineColourId, border);
    };
    setupSlider(tempoSlider, 40, 240, 120, juce::Slider::LinearHorizontal);
    setupSlider(gainSlider, 0, 1.5, 0.85, juce::Slider::LinearHorizontal);
    setupSlider(panSlider, -1, 1, 0, juce::Slider::LinearHorizontal);
    tempoSlider.setNumDecimalPlacesToDisplay(0);
    tempoSlider.onValueChange = [this] { tempo = static_cast<int>(tempoSlider.getValue()); repaint(); };
    gainSlider.onValueChange = [this] { if (auto* t = trackSlots[static_cast<size_t>(selectedTrack.load())].load()) t->gain.store(static_cast<float>(gainSlider.getValue())); };
    panSlider.onValueChange = [this] { if (auto* t = trackSlots[static_cast<size_t>(selectedTrack.load())].load()) t->pan.store(static_cast<float>(panSlider.getValue())); };
    const std::array<juce::Component*, 3> sliders = { &tempoSlider, &gainSlider, &panSlider };
    for (auto* c : sliders) addAndMakeVisible(c);

    projectLabel.setText("MIDNIGHT SESSION", juce::dontSendNotification);
    projectLabel.setFont(juce::Font(juce::FontOptions(17.0f, juce::Font::bold)));
    projectLabel.setColour(juce::Label::textColourId, white);
    statusLabel.setText("AUDIO ENGINE STARTING", juce::dontSendNotification);
    statusLabel.setFont(juce::Font(juce::FontOptions(10.5f)));
    statusLabel.setColour(juce::Label::textColourId, mutedText);
    timeLabel.setText("00:00.000", juce::dontSendNotification);
    timeLabel.setFont(juce::Font(juce::FontOptions(juce::Font::getDefaultMonospacedFontName(), 22.0f, juce::Font::bold)));
    timeLabel.setColour(juce::Label::textColourId, white);
    timeLabel.setJustificationType(juce::Justification::centred);
    tempoLabel.setText("TEMPO", juce::dontSendNotification);
    gainLabel.setText("LEVEL", juce::dontSendNotification);
    panLabel.setText("PAN", juce::dontSendNotification);
    for (auto* l : { &projectLabel, &statusLabel, &timeLabel, &tempoLabel, &gainLabel, &panLabel }) addAndMakeVisible(l);
    for (auto* l : { &tempoLabel, &gainLabel, &panLabel }) l->setColour(juce::Label::textColourId, mutedText);
    updateTrackControls();
    startTimerHz(20);
}

MainComponent::~MainComponent()
{
    shutdownAudio();
}

void MainComponent::prepareToPlay(int, double rate)
{
    sampleRate = rate;
    statusLabel.setText("ENGINE ONLINE  •  " + juce::String(rate / 1000.0, 1) + " kHz", juce::dontSendNotification);
}

void MainComponent::getNextAudioBlock(const juce::AudioSourceChannelInfo& info)
{
    auto* out = info.buffer;
    if (out == nullptr) return;
    juce::ScopedTryLock lock(audioDataLock);
    if (!lock.isLocked()) { out->clear(info.startSample, info.numSamples); return; }

    const auto* device = deviceManager.getCurrentAudioDevice();
    const int inputs = device != nullptr ? device->getActiveInputChannels().countNumberOfSetBits() : 0;
    const int outputs = device != nullptr ? device->getActiveOutputChannels().countNumberOfSetBits() : 0;
    const int selected = selectedTrack.load();
    Track* armedTrack = selected >= 0 && selected < maxTracks ? trackSlots[static_cast<size_t>(selected)].load() : nullptr;

    if (recording.load() && armedTrack != nullptr && armedTrack->armed.load())
    {
        const int start = armedTrack->length.load();
        const int capacity = armedTrack->audio.getNumSamples();
        const int amount = juce::jmin(info.numSamples, juce::jmax(0, capacity - start));
        if (inputs > 0 && amount > 0)
        {
            for (int ch = 0; ch < 2; ++ch)
                armedTrack->audio.copyFrom(ch, start, *out, juce::jmin(ch, inputs - 1), info.startSample, amount);
            armedTrack->length.store(start + amount);
        }
        else if (amount <= 0) recording.store(false);
    }

    out->clear(info.startSample, info.numSamples);
    const int count = trackCount.load();
    bool hasSolo = false;
    for (int i = 0; i < count; ++i)
        if (auto* t = trackSlots[static_cast<size_t>(i)].load()) hasSolo = hasSolo || t->solo.load();

    if (playing.load())
    {
        int pos = playhead.load();
        int sessionEnd = getSessionLength();
        if (pos >= sessionEnd)
        {
            if (loopEnabled.load() && sessionEnd > 0) { pos = 0; playhead.store(0); }
            else playing.store(false);
        }
        if (playing.load() && sessionEnd > pos)
        {
            const int amount = juce::jmin(info.numSamples, sessionEnd - pos);
            for (int i = 0; i < count; ++i)
            {
                auto* t = trackSlots[static_cast<size_t>(i)].load();
                if (t == nullptr || t->muted.load() || (hasSolo && !t->solo.load())) continue;
                const int available = juce::jmax(0, t->length.load() - pos);
                const int n = juce::jmin(amount, available);
                if (n <= 0) continue;
                const float gain = t->gain.load();
                const float pan = t->pan.load();
                const float left = gain * (pan > 0.0f ? 1.0f - pan : 1.0f);
                const float right = gain * (pan < 0.0f ? 1.0f + pan : 1.0f);
                if (outputs > 0 && out->getNumChannels() > 0) out->addFrom(0, info.startSample, t->audio, 0, pos, n, left);
                if (outputs > 1 && out->getNumChannels() > 1) out->addFrom(1, info.startSample, t->audio, 1, pos, n, right);
            }
            playhead.store(pos + amount);
            if (loopEnabled.load() && pos + amount >= sessionEnd) playhead.store(0);
            else if (pos + amount >= sessionEnd) playing.store(false);
        }
    }

    if (clickEnabled.load() && (recording.load() || playing.load()) && outputs > 0)
    {
        const int beatSamples = juce::jmax(1, static_cast<int>(sampleRate * 60.0 / juce::jmax(40, tempo.load())));
        for (int i = 0; i < info.numSamples; ++i)
        {
            if (clickCountdown <= 0) clickCountdown = beatSamples;
            const float envelope = clickCountdown > beatSamples - 480 ? static_cast<float>(clickCountdown - (beatSamples - 480)) / 480.0f : 0.0f;
            if (envelope > 0.0f)
            {
                const float sample = std::sin(static_cast<float>(clickPhase)) * envelope * 0.12f;
                if (out->getNumChannels() > 0) out->addSample(0, info.startSample + i, sample);
                if (out->getNumChannels() > 1) out->addSample(1, info.startSample + i, sample);
                clickPhase += 2.0 * juce::MathConstants<double>::pi * 1500.0 / sampleRate;
            }
            --clickCountdown;
        }
    }
}

void MainComponent::releaseResources() {}

void MainComponent::addTrack()
{
    if (static_cast<int>(tracks.size()) >= maxTracks) { statusLabel.setText("TRACK LIMIT REACHED", juce::dontSendNotification); return; }
    auto track = std::make_unique<Track>();
    const int index = static_cast<int>(tracks.size());
    track->name = "Audio " + juce::String(index + 1);
    track->colour = palette[index % (sizeof(palette) / sizeof(palette[0]))];
    track->armed.store(index == 0);
    Track* raw = track.get();
    tracks.push_back(std::move(track));
    trackSlots[static_cast<size_t>(index)].store(raw);
    trackCount.store(index + 1);
    selectedTrack.store(index);
    updateTrackControls();
    statusLabel.setText("TRACK CREATED  •  " + raw->name, juce::dontSendNotification);
    repaint();
}

void MainComponent::selectTrack(int index)
{
    if (index < 0 || index >= trackCount.load()) return;
    selectedTrack.store(index);
    updateTrackControls();
    repaint();
}

void MainComponent::updateTrackControls()
{
    const int index = selectedTrack.load();
    Track* t = index >= 0 && index < maxTracks ? trackSlots[static_cast<size_t>(index)].load() : nullptr;
    if (t == nullptr) return;
    muteButton.setToggleState(t->muted.load(), juce::dontSendNotification);
    soloButton.setToggleState(t->solo.load(), juce::dontSendNotification);
    armButton.setToggleState(t->armed.load(), juce::dontSendNotification);
    gainSlider.setValue(t->gain.load(), juce::dontSendNotification);
    panSlider.setValue(t->pan.load(), juce::dontSendNotification);
}

void MainComponent::importAudio()
{
    auto chooser = std::make_shared<juce::FileChooser>("Import audio", juce::File::getSpecialLocation(juce::File::userMusicDirectory), "*.wav;*.aif;*.aiff;*.flac;*.mp3");
    chooser->launchAsync(juce::FileBrowserComponent::openMode | juce::FileBrowserComponent::canSelectFiles,
        [this, chooser](const juce::FileChooser& fc)
        {
            const auto file = fc.getResult();
            if (file == juce::File{}) return;
            const int index = selectedTrack.load();
            Track* t = index >= 0 && index < maxTracks ? trackSlots[static_cast<size_t>(index)].load() : nullptr;
            if (t == nullptr) return;
            juce::AudioFormatManager formats;
            formats.registerBasicFormats();
            std::unique_ptr<juce::AudioFormatReader> reader(formats.createReaderFor(file));
            if (reader == nullptr) { statusLabel.setText("IMPORT FAILED  •  UNSUPPORTED AUDIO FILE", juce::dontSendNotification); return; }
            const auto maxLength = static_cast<juce::int64>(reader->sampleRate * maxTrackSeconds);
            const int length = static_cast<int>(juce::jmin<juce::int64>(reader->lengthInSamples, maxLength));
            if (length <= 0) return;
            stopTransport();
            const juce::ScopedLock lock(audioDataLock);
            t->audio.setSize(2, length);
            t->audio.clear();
            reader->read(&t->audio, 0, length, 0, true, true);
            t->length.store(length);
            playhead.store(0);
            statusLabel.setText("IMPORTED  •  " + file.getFileName(), juce::dontSendNotification);
            repaint();
        });
}

int MainComponent::getSessionLength() const
{
    int length = 0;
    const int count = trackCount.load();
    for (int i = 0; i < count; ++i)
        if (auto* t = trackSlots[static_cast<size_t>(i)].load()) length = juce::jmax(length, t->length.load());
    return length;
}

void MainComponent::startRecording()
{
    const int index = selectedTrack.load();
    Track* t = index >= 0 && index < maxTracks ? trackSlots[static_cast<size_t>(index)].load() : nullptr;
    if (t == nullptr) return;
    stopTransport();
    {
        const juce::ScopedLock lock(audioDataLock);
        t->audio.setSize(2, static_cast<int>(sampleRate * maxTrackSeconds));
        t->audio.clear();
        t->length.store(0);
    }
    t->armed.store(true);
    armButton.setToggleState(true, juce::dontSendNotification);
    playhead.store(0);
    clickCountdown = 0;
    recording.store(true);
    statusLabel.setText("RECORDING  •  " + t->name, juce::dontSendNotification);
}

void MainComponent::startPlayback()
{
    if (getSessionLength() <= 0) { statusLabel.setText("NO AUDIO  •  IMPORT OR RECORD A CLIP FIRST", juce::dontSendNotification); return; }
    if (playhead.load() >= getSessionLength()) playhead.store(0);
    recording.store(false);
    playing.store(true);
    clickCountdown = 0;
    statusLabel.setText("PLAYING  •  SESSION MIX", juce::dontSendNotification);
}

void MainComponent::stopTransport()
{
    recording.store(false);
    playing.store(false);
    statusLabel.setText("STOPPED  •  " + juce::String(getSessionLength() / sampleRate, 2) + " s", juce::dontSendNotification);
}

void MainComponent::exportMix()
{
    stopTransport();
    const int length = getSessionLength();
    if (length <= 0) { statusLabel.setText("NOTHING TO EXPORT", juce::dontSendNotification); return; }
    auto chooser = std::make_shared<juce::FileChooser>("Export stereo WAV", juce::File::getSpecialLocation(juce::File::userMusicDirectory).getChildFile("EC Studio Mix.wav"), "*.wav");
    chooser->launchAsync(juce::FileBrowserComponent::saveMode | juce::FileBrowserComponent::canSelectFiles,
        [this, chooser, length](const juce::FileChooser& fc)
        {
            const auto file = fc.getResult();
            if (file == juce::File{}) return;
            juce::AudioBuffer<float> mix(2, length);
            mix.clear();
            const juce::ScopedLock lock(audioDataLock);
            const int count = trackCount.load();
            bool hasSolo = false;
            for (int i = 0; i < count; ++i)
                if (auto* t = trackSlots[static_cast<size_t>(i)].load()) hasSolo = hasSolo || t->solo.load();
            for (int i = 0; i < count; ++i)
            {
                auto* t = trackSlots[static_cast<size_t>(i)].load();
                if (t == nullptr || t->muted.load() || (hasSolo && !t->solo.load())) continue;
                const int n = juce::jmin(length, t->length.load());
                const float gain = t->gain.load(), pan = t->pan.load();
                if (n > 0)
                {
                    mix.addFrom(0, 0, t->audio, 0, 0, n, gain * (pan > 0.0f ? 1.0f - pan : 1.0f));
                    mix.addFrom(1, 0, t->audio, 1, 0, n, gain * (pan < 0.0f ? 1.0f + pan : 1.0f));
                }
            }
            auto stream = std::unique_ptr<juce::FileOutputStream>(file.createOutputStream());
            if (stream == nullptr || !stream->openedOk()) { statusLabel.setText("EXPORT FAILED  •  CANNOT WRITE FILE", juce::dontSendNotification); return; }
            juce::WavAudioFormat wav;
            auto* raw = wav.createWriterFor(stream.get(), sampleRate, 2u, 24, juce::StringPairArray(), 0);
            if (raw == nullptr) { statusLabel.setText("EXPORT FAILED  •  WAV ENCODER", juce::dontSendNotification); return; }
            stream.release();
            std::unique_ptr<juce::AudioFormatWriter> writer(raw);
            const bool ok = writer->writeFromAudioSampleBuffer(mix, 0, length);
            writer.reset();
            statusLabel.setText(ok ? "MIX EXPORTED  •  " + file.getFileName() : "EXPORT FAILED", juce::dontSendNotification);
        });
}

void MainComponent::drawWaveform(juce::Graphics& g, juce::Rectangle<int> area, const Track& t)
{
    g.setColour(juce::Colour(0xff193b55));
    g.fillRoundedRectangle(area.toFloat(), 5.0f);
    const int count = t.length.load();
    if (count <= 0 || t.audio.getNumSamples() <= 0) return;
    const juce::ScopedTryLock lock(const_cast<juce::CriticalSection&>(audioDataLock));
    if (!lock.isLocked()) return;
    const int columns = juce::jmax(1, area.getWidth() - 8);
    const int step = juce::jmax(1, count / columns);
    const float mid = area.getCentreY(), amp = area.getHeight() * 0.42f;
    juce::Path wave;
    for (int x = 0; x < columns; ++x)
    {
        const int start = x * step;
        if (start >= count) break;
        float peak = 0.0f;
        for (int n = start; n < juce::jmin(count, start + step); ++n)
            peak = juce::jmax(peak, std::abs(t.audio.getSample(0, n)));
        const float px = static_cast<float>(area.getX() + 4 + x);
        const float h = juce::jmax(1.0f, peak * amp);
        wave.startNewSubPath(px, mid - h);
        wave.lineTo(px, mid + h);
    }
    g.setColour(accent2);
    g.strokePath(wave, juce::PathStrokeType(1.0f));
}

void MainComponent::drawTrack(juce::Graphics& g, juce::Rectangle<int> area, int index)
{
    auto* t = trackSlots[static_cast<size_t>(index)].load();
    if (t == nullptr) return;
    const bool selected = index == selectedTrack.load();
    g.setColour(selected ? juce::Colour(0xff202e3d) : (index % 2 ? surface : panel));
    g.fillRect(area);
    auto header = area.removeFromLeft(190);
    g.setColour(t->colour);
    g.fillRect(header.removeFromLeft(4));
    header.reduce(12, 0);
    g.setColour(white);
    g.setFont(juce::Font(juce::FontOptions(12.0f, juce::Font::bold)));
    g.drawText(t->name, header.removeFromTop(25), juce::Justification::centredLeft);
    g.setColour(mutedText);
    g.setFont(juce::Font(juce::FontOptions(9.5f)));
    const juce::String flags = juce::String(t->muted.load() ? "M " : "") + (t->solo.load() ? "S " : "") + (t->armed.load() ? "● REC ARM" : "STEREO AUDIO");
    g.drawText(flags, header.removeFromTop(18), juce::Justification::centredLeft);
    auto clip = area.reduced(6, 10);
    if (t->length.load() > 0)
    {
        const int clipWidth = juce::jmax(100, juce::jmin(clip.getWidth(), 100 + static_cast<int>(t->length.load() / sampleRate * 18.0)));
        clip = clip.withWidth(clipWidth);
        g.setColour(t->colour.withAlpha(0.32f));
        g.fillRoundedRectangle(clip.toFloat(), 5.0f);
        g.setColour(t->colour);
        g.drawRoundedRectangle(clip.toFloat(), 5.0f, 1.0f);
        drawWaveform(g, clip.reduced(4, 5), *t);
    }
    else
    {
        g.setColour(border);
        g.drawRoundedRectangle(clip.withWidth(juce::jmin(clip.getWidth(), 180)).toFloat(), 5.0f, 1.0f);
        g.setColour(mutedText);
        g.setFont(juce::Font(juce::FontOptions(10.0f)));
        g.drawText("DROP OR IMPORT AUDIO", clip.withWidth(180), juce::Justification::centred);
    }
    g.setColour(border);
    g.drawHorizontalLine(area.getBottom(), static_cast<float>(area.getX()), static_cast<float>(getWidth() - 220));
}

void MainComponent::paint(juce::Graphics& g)
{
    g.fillAll(bg);
    g.setColour(surface);
    g.fillRect(0, 0, getWidth(), 58);
    g.setColour(accent);
    g.fillRect(0, 57, getWidth(), 2);
    g.setColour(white);
    g.setFont(juce::Font(juce::FontOptions(23.0f, juce::Font::bold)));
    g.drawText("EC", 22, 8, 32, 28, juce::Justification::centredLeft);
    g.setColour(accent2);
    g.drawText("STUDIO", 55, 8, 94, 28, juce::Justification::centredLeft);
    g.setColour(mutedText);
    g.setFont(juce::Font(juce::FontOptions(9.0f)));
    g.drawText("PRO AUDIO WORKSTATION", 23, 35, 170, 14, juce::Justification::centredLeft);

    g.setColour(panel);
    g.fillRect(0, 60, getWidth(), 66);
    g.setColour(border);
    g.drawHorizontalLine(125, 0.0f, static_cast<float>(getWidth()));
    g.setColour(surface);
    g.fillRoundedRectangle(juce::Rectangle<float>(getWidth() / 2.0f - 95.0f, 9.0f, 190.0f, 42.0f), 8.0f);
    g.setColour(mutedText);
    g.setFont(juce::Font(juce::FontOptions(9.0f, juce::Font::bold)));
    g.drawText("SESSION POSITION", getWidth() / 2 - 82, 11, 164, 12, juce::Justification::centred);
    g.setColour(border);
    g.drawHorizontalLine(126, 0.0f, static_cast<float>(getWidth()));

    const int left = 190, right = 220, top = 136, bottom = mixerVisible ? getHeight() - 194 : getHeight() - 24;
    g.setColour(surface);
    g.fillRect(0, top, left, bottom - top);
    g.setColour(border);
    g.drawVerticalLine(left, static_cast<float>(top), static_cast<float>(bottom));
    g.setColour(mutedText);
    g.setFont(juce::Font(juce::FontOptions(10.0f, juce::Font::bold)));
    g.drawText("BROWSER", 18, 151, 150, 18, juce::Justification::centredLeft);
    g.setColour(white);
    g.setFont(juce::Font(juce::FontOptions(11.5f)));
    g.drawText("⌕   Audio Files", 22, 184, 150, 22, juce::Justification::centredLeft);
    g.drawText("♫   Instruments", 22, 216, 150, 22, juce::Justification::centredLeft);
    g.drawText("◷   Recent", 22, 248, 150, 22, juce::Justification::centredLeft);
    g.setColour(mutedText);
    g.setFont(juce::Font(juce::FontOptions(9.5f)));
    g.drawText("DRAG AUDIO INTO A TRACK", 18, 294, 160, 18, juce::Justification::centredLeft);
    g.drawText("EC AUDIO  •  V1.0", 18, bottom - 24, 150, 16, juce::Justification::centredLeft);

    const int mainX = left, mainW = getWidth() - left - right;
    g.setColour(panel2);
    g.fillRect(mainX, top, mainW, 44);
    g.setColour(mutedText);
    g.setFont(juce::Font(juce::FontOptions(9.5f, juce::Font::bold)));
    g.drawText("ARRANGEMENT", mainX + 14, top + 12, 130, 18, juce::Justification::centredLeft);
    const int rulerY = top + 44;
    g.setColour(surface);
    g.fillRect(mainX, rulerY, mainW, 28);
    const int laneTop = rulerY + 28;
    for (int bar = 1; bar <= 64; ++bar)
    {
        const int x = mainX + 205 + (bar - 1) * 42;
        if (x >= mainX + mainW) break;
        g.setColour(bar % 4 == 1 ? mutedText : border);
        g.drawVerticalLine(x, static_cast<float>(rulerY), static_cast<float>(bottom));
        if (bar % 4 == 1)
        {
            g.setColour(mutedText);
            g.setFont(juce::Font(juce::FontOptions(9.0f)));
            g.drawText(juce::String(bar), x + 4, rulerY + 6, 28, 14, juce::Justification::centredLeft);
        }
    }
    const int laneHeight = 76;
    for (int i = 0; i < trackCount.load(); ++i)
    {
        const int y = laneTop + i * laneHeight;
        if (y + laneHeight > bottom) break;
        drawTrack(g, { mainX, y, mainW, laneHeight }, i);
    }
    const double seconds = static_cast<double>(playhead.load()) / sampleRate;
    const int playX = mainX + 205 + static_cast<int>(seconds * tempo.load() / 60.0 * 42.0 / 4.0);
    g.setColour(juce::Colour(0xffffe18a));
    g.drawVerticalLine(playX, static_cast<float>(rulerY), static_cast<float>(bottom));
    juce::Path head;
    head.startNewSubPath(static_cast<float>(playX - 5), static_cast<float>(rulerY));
    head.lineTo(static_cast<float>(playX + 5), static_cast<float>(rulerY));
    head.lineTo(static_cast<float>(playX), static_cast<float>(rulerY + 7));
    head.closeSubPath();
    g.fillPath(head);

    const int inspectorX = getWidth() - right;
    g.setColour(surface);
    g.fillRect(inspectorX, top, right, bottom - top);
    g.setColour(border);
    g.drawVerticalLine(inspectorX, static_cast<float>(top), static_cast<float>(bottom));
    g.setColour(mutedText);
    g.setFont(juce::Font(juce::FontOptions(10.0f, juce::Font::bold)));
    g.drawText("CHANNEL INSPECTOR", inspectorX + 16, 151, 190, 18, juce::Justification::centredLeft);
    g.setColour(white);
    g.setFont(juce::Font(juce::FontOptions(13.0f, juce::Font::bold)));
    const int si = selectedTrack.load();
    if (si >= 0 && si < trackCount.load()) g.drawText(tracks[static_cast<size_t>(si)]->name, inspectorX + 16, 180, 185, 24, juce::Justification::centredLeft);

    if (mixerVisible)
    {
        const int y = getHeight() - 194;
        g.setColour(surface);
        g.fillRect(0, y, getWidth(), 194);
        g.setColour(border);
        g.drawHorizontalLine(y, 0.0f, static_cast<float>(getWidth()));
        g.setColour(mutedText);
        g.setFont(juce::Font(juce::FontOptions(10.0f, juce::Font::bold)));
        g.drawText("MIXER  /  CHANNEL STRIPS", 18, y + 8, 240, 16, juce::Justification::centredLeft);
        const int count = juce::jmin(trackCount.load(), 9);
        for (int i = 0; i < count; ++i)
        {
            auto* t = trackSlots[static_cast<size_t>(i)].load();
            if (t == nullptr) continue;
            const int x = 18 + i * 116;
            g.setColour(panel);
            g.fillRoundedRectangle(static_cast<float>(x), static_cast<float>(y + 31), 104.0f, 148.0f, 6.0f);
            g.setColour(t->colour);
            g.fillRect(x, y + 31, 104, 3);
            g.setColour(white);
            g.setFont(juce::Font(juce::FontOptions(10.0f, juce::Font::bold)));
            g.drawText(t->name, x + 5, y + 41, 94, 16, juce::Justification::centred);
            g.setColour(mutedText);
            g.setFont(juce::Font(juce::FontOptions(9.0f)));
            g.drawText(juce::String(t->gain.load(), 2) + " dB", x + 5, y + 60, 94, 14, juce::Justification::centred);
            g.setColour(border);
            g.drawRoundedRectangle(static_cast<float>(x + 47), static_cast<float>(y + 82), 10.0f, 70.0f, 3.0f, 1.0f);
            g.setColour(accent);
            const float level = juce::jlimit(0.0f, 1.0f, t->gain.load() / 1.5f);
            g.fillRoundedRectangle(static_cast<float>(x + 49), static_cast<float>(y + 150 - level * 64.0f), 6.0f, level * 64.0f, 2.0f);
        }
    }
}

void MainComponent::mouseDown(const juce::MouseEvent& e)
{
    const int laneTop = 136 + 44 + 28;
    const int index = (e.y - laneTop) / 76;
    if (e.x >= 190 && e.x < getWidth() - 220 && e.y >= laneTop && index >= 0 && index < trackCount.load())
        selectTrack(index);
}

void MainComponent::timerCallback()
{
    const double seconds = static_cast<double>(playing.load() ? playhead.load() : getSessionLength()) / sampleRate;
    timeLabel.setText(juce::String::formatted("%02d:%02d.%03d", static_cast<int>(seconds) / 60,
        static_cast<int>(seconds) % 60, static_cast<int>(std::fmod(seconds, 1.0) * 1000.0)), juce::dontSendNotification);
    repaint();
}

void MainComponent::resized()
{
    projectLabel.setBounds(210, 10, 230, 26);
    statusLabel.setBounds(210, 34, 340, 17);
    timeLabel.setBounds(getWidth() / 2 - 90, 14, 180, 30);
    recordButton.setBounds(getWidth() - 478, 74, 38, 38);
    playButton.setBounds(getWidth() - 432, 74, 38, 38);
    stopButton.setBounds(getWidth() - 386, 74, 38, 38);
    loopButton.setBounds(getWidth() - 330, 78, 72, 30);
    clickButton.setBounds(getWidth() - 252, 78, 100, 30);
    tempoLabel.setBounds(getWidth() - 142, 67, 65, 15);
    tempoSlider.setBounds(getWidth() - 150, 83, 135, 30);
    newTrackButton.setBounds(16, 74, 94, 38);
    importButton.setBounds(118, 74, 116, 38);
    exportButton.setBounds(246, 74, 108, 38);
    mixerButton.setBounds(366, 74, 80, 38);
    const int inspector = getWidth() - 204;
    muteButton.setBounds(inspector + 14, 218, 48, 28);
    soloButton.setBounds(inspector + 68, 218, 48, 28);
    armButton.setBounds(inspector + 122, 218, 48, 28);
    gainLabel.setBounds(inspector + 14, 266, 70, 18);
    gainSlider.setBounds(inspector + 14, 286, 180, 28);
    panLabel.setBounds(inspector + 14, 322, 70, 18);
    panSlider.setBounds(inspector + 14, 342, 180, 28);
}
