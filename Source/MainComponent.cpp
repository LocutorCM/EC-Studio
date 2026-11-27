#include "MainComponent.h"
#include <cmath>

namespace
{
const juce::Colour bg(0xff17191e), top(0xff202329), panel(0xff252930), panel2(0xff2d323a);
const juce::Colour line(0xff3b424c), blue(0xff3987e8), blueBright(0xff55a4ff);
const juce::Colour text(0xffe6e9ee), secondary(0xff9ba4b1), green(0xff75c99a);
const juce::Colour trackBlue(0xff3c83c9), trackPurple(0xff9174c8), trackGold(0xffc69a53);
}

MainComponent::MainComponent()
{
    setSize(1440, 900);
    setAudioChannels(2, 2);

    tracks.push_back({"Audio 1", trackBlue});
    tracks.push_back({"Audio 2", trackPurple});

    auto styleButton = [](juce::TextButton& b, juce::Colour colour, bool prominent = false)
    {
        b.setColour(juce::TextButton::buttonColourId, colour);
        b.setColour(juce::TextButton::buttonOnColourId, colour.brighter(0.18f));
        b.setColour(juce::TextButton::textColourOffId, text);
        b.setColour(juce::TextButton::textColourOnId, text);
        b.setConnectedEdges(0);
        b.setMouseCursor(juce::MouseCursor::PointingHandCursor);
        b.setButtonText(b.getButtonText());
        if (prominent) b.setColour(juce::TextButton::buttonColourId, colour);
    };

    styleButton(recordButton, juce::Colour(0xffc94c55), true);
    styleButton(playButton, blue, true);
    styleButton(stopButton, panel2);
    styleButton(exportButton, panel2);
    styleButton(addTrackButton, blue);
    styleButton(mixerButton, panel2);
    styleButton(loopButton, panel2);
    styleButton(metronomeButton, panel2);

    recordButton.setTooltip("Record audio input");
    playButton.setTooltip("Play session");
    stopButton.setTooltip("Stop playback or recording");
    exportButton.setTooltip("Export stereo WAV");
    addTrackButton.setTooltip("Add an audio track to the arrangement");
    mixerButton.setTooltip("Show or hide the mixer");

    recordButton.onClick = [this] { startRecording(); };
    playButton.onClick = [this] { startPlayback(); };
    stopButton.onClick = [this] { stopTransport(); };
    exportButton.onClick = [this] { exportWav(); };
    addTrackButton.onClick = [this] { addAudioTrack(); };
    mixerButton.onClick = [this] { toggleMixer(); };
    loopButton.onClick = [this] { statusLabel.setText("LOOP MODE  •  LOOP RANGE EDITING IS COMING NEXT", juce::dontSendNotification); };
    metronomeButton.onClick = [this] { statusLabel.setText("CLICK TRACK  •  METRONOME ENGINE IS COMING NEXT", juce::dontSendNotification); };

    for (auto* button : {&recordButton, &playButton, &stopButton, &exportButton,
                         &addTrackButton, &mixerButton, &loopButton, &metronomeButton})
        addAndMakeVisible(button);

    tempoBox.addItem("80 BPM", 80);
    tempoBox.addItem("100 BPM", 100);
    tempoBox.addItem("110 BPM", 110);
    tempoBox.addItem("120 BPM", 120);
    tempoBox.addItem("128 BPM", 128);
    tempoBox.addItem("140 BPM", 140);
    tempoBox.addItem("160 BPM", 160);
    tempoBox.setSelectedId(120, juce::dontSendNotification);
    tempoBox.onChange = [this]
    {
        tempo = tempoBox.getSelectedId();
        repaint();
    };
    tempoBox.setColour(juce::ComboBox::backgroundColourId, panel2);
    tempoBox.setColour(juce::ComboBox::textColourId, text);
    tempoBox.setColour(juce::ComboBox::outlineColourId, line);
    addAndMakeVisible(tempoBox);

    projectLabel.setText("Untitled Session", juce::dontSendNotification);
    projectLabel.setFont(juce::Font(juce::FontOptions(16.0f, juce::Font::bold)));
    projectLabel.setColour(juce::Label::textColourId, text);
    addAndMakeVisible(projectLabel);

    statusLabel.setText("AUDIO ENGINE INITIALIZING", juce::dontSendNotification);
    statusLabel.setFont(juce::Font(juce::FontOptions(11.0f)));
    statusLabel.setColour(juce::Label::textColourId, secondary);
    addAndMakeVisible(statusLabel);

    timeLabel.setText("00:00:000", juce::dontSendNotification);
    timeLabel.setFont(juce::Font(juce::FontOptions(juce::Font::getDefaultMonospacedFontName(), 21.0f, juce::Font::bold)));
    timeLabel.setColour(juce::Label::textColourId, text);
    timeLabel.setJustificationType(juce::Justification::centred);
    addAndMakeVisible(timeLabel);

    startTimerHz(20);
}

MainComponent::~MainComponent()
{
    shutdownAudio();
}

void MainComponent::prepareToPlay(int, double rate)
{
    currentSampleRate = rate;
    maxRecordSamples = static_cast<int>(rate * 600.0);
    const juce::ScopedLock lock(exportLock);
    recordedAudio.setSize(2, maxRecordSamples);
    recordedAudio.clear();
    recordedSamples.store(0);
    playhead.store(0);
    statusLabel.setText("AUDIO ENGINE READY  •  " + juce::String(rate / 1000.0, 1) + " kHz",
                        juce::dontSendNotification);
}

void MainComponent::getNextAudioBlock(const juce::AudioSourceChannelInfo& info)
{
    auto* out = info.buffer;
    if (out == nullptr) return;

    const auto* audioDevice = deviceManager.getCurrentAudioDevice();
    const int inputs = audioDevice != nullptr
        ? juce::jmin(audioDevice->getActiveInputChannels().countNumberOfSetBits(), 2) : 0;
    const int outputs = audioDevice != nullptr
        ? juce::jmin(audioDevice->getActiveOutputChannels().countNumberOfSetBits(), 2) : 0;

    if (recording.load() && inputs > 0)
    {
        const int start = recordedSamples.load();
        const int amount = juce::jmin(info.numSamples, juce::jmax(0, maxRecordSamples - start));
        if (amount > 0)
        {
            for (int ch = 0; ch < 2; ++ch)
                recordedAudio.copyFrom(ch, start, *out, juce::jmin(ch, inputs - 1), info.startSample, amount);
            recordedSamples.store(start + amount);
        }
        else recording.store(false);
    }

    out->clear(info.startSample, info.numSamples);

    if (playing.load())
    {
        const int pos = playhead.load();
        const int amount = juce::jmin(info.numSamples, juce::jmax(0, recordedSamples.load() - pos));
        if (amount > 0)
        {
            const int channelsToCopy = juce::jmin(outputs, out->getNumChannels());
            for (int ch = 0; ch < channelsToCopy; ++ch)
                out->copyFrom(ch, info.startSample, recordedAudio, ch, pos, amount);
            playhead.store(pos + amount);
        }
        else playing.store(false);
    }
}

void MainComponent::releaseResources() {}

void MainComponent::startRecording()
{
    playing.store(false);
    recordedSamples.store(0);
    playhead.store(0);
    recording.store(true);
    statusLabel.setText("RECORDING  •  CAPTURING AUDIO INPUT", juce::dontSendNotification);
}

void MainComponent::startPlayback()
{
    if (recordedSamples.load() <= 0)
    {
        statusLabel.setText("NO AUDIO RECORDED  •  PRESS RECORD TO CAPTURE A TAKE", juce::dontSendNotification);
        return;
    }
    if (playhead.load() >= recordedSamples.load()) playhead.store(0);
    recording.store(false);
    playing.store(true);
    statusLabel.setText("PLAYING  •  SESSION AUDIO", juce::dontSendNotification);
}

void MainComponent::stopTransport()
{
    recording.store(false);
    playing.store(false);
    statusLabel.setText("STOPPED  •  " + juce::String(recordedSamples.load() / currentSampleRate, 2) + " s RECORDED",
                        juce::dontSendNotification);
}

void MainComponent::addAudioTrack()
{
    static const juce::Colour palette[] = { trackBlue, trackPurple, trackGold, green, juce::Colour(0xffbd718e) };
    const int index = static_cast<int>(tracks.size());
    tracks.push_back({"Audio " + juce::String(index + 1), palette[index % 5]});
    statusLabel.setText("TRACK ADDED  •  " + tracks.back().name, juce::dontSendNotification);
    repaint();
}

void MainComponent::toggleMixer()
{
    mixerVisible = !mixerVisible;
    resized();
    repaint();
}

void MainComponent::exportWav()
{
    stopTransport();
    const int length = recordedSamples.load();
    if (length <= 0)
    {
        statusLabel.setText("NOTHING TO EXPORT  •  RECORD A TAKE FIRST", juce::dontSendNotification);
        return;
    }

    auto chooser = std::make_shared<juce::FileChooser>(
        "Export audio as WAV",
        juce::File::getSpecialLocation(juce::File::userMusicDirectory).getChildFile("EC Studio Mix.wav"),
        "*.wav");

    chooser->launchAsync(juce::FileBrowserComponent::saveMode | juce::FileBrowserComponent::canSelectFiles,
        [this, chooser, length](const juce::FileChooser& fc)
        {
            const auto file = fc.getResult();
            if (file == juce::File{}) return;

            auto stream = std::unique_ptr<juce::FileOutputStream>(file.createOutputStream());
            if (stream == nullptr || !stream->openedOk())
            {
                statusLabel.setText("EXPORT FAILED  •  COULD NOT CREATE FILE", juce::dontSendNotification);
                return;
            }

            juce::WavAudioFormat wav;
            auto* rawWriter = wav.createWriterFor(stream.get(), currentSampleRate, 2u, 24,
                                                   juce::StringPairArray(), 0);
            if (rawWriter != nullptr) stream.release();
            std::unique_ptr<juce::AudioFormatWriter> writer(rawWriter);
            if (writer == nullptr)
            {
                statusLabel.setText("EXPORT FAILED  •  WAV WRITER ERROR", juce::dontSendNotification);
                return;
            }

            const juce::ScopedLock lock(exportLock);
            const bool ok = writer->writeFromAudioSampleBuffer(recordedAudio, 0, length);
            writer.reset();
            statusLabel.setText(ok ? "EXPORTED  •  " + file.getFileName() : "EXPORT FAILED",
                                juce::dontSendNotification);
        });
}

void MainComponent::timerCallback()
{
    const double t = (playing.load() ? playhead.load() : recordedSamples.load()) / currentSampleRate;
    timeLabel.setText(juce::String::formatted("%02d:%02d:%03d",
        static_cast<int>(t) / 60, static_cast<int>(t) % 60,
        static_cast<int>(std::fmod(t, 1.0) * 1000.0)), juce::dontSendNotification);
    repaint();
}

void MainComponent::drawWaveform(juce::Graphics& g, juce::Rectangle<int> area)
{
    g.setColour(juce::Colour(0xff253c54));
    g.fillRoundedRectangle(area.toFloat(), 5.0f);
    const int count = recordedSamples.load();
    if (count <= 0) return;

    const int columns = juce::jmax(1, area.getWidth() - 8);
    const int step = juce::jmax(1, count / columns);
    const float mid = area.getCentreY();
    const float amplitude = area.getHeight() * 0.43f;
    juce::Path waveform;

    for (int x = 0; x < columns; ++x)
    {
        const int start = x * step;
        if (start >= count) break;
        float peak = 0.0f;
        const int end = juce::jmin(count, start + step);
        for (int n = start; n < end; ++n)
            peak = juce::jmax(peak, std::abs(recordedAudio.getSample(0, n)));

        const float px = static_cast<float>(area.getX() + 4 + x);
        const float height = juce::jmax(1.0f, peak * amplitude);
        waveform.startNewSubPath(px, mid - height);
        waveform.lineTo(px, mid + height);
    }

    g.setColour(juce::Colour(0xff9bd4ff));
    g.strokePath(waveform, juce::PathStrokeType(1.0f));
}

void MainComponent::drawTrackLane(juce::Graphics& g, juce::Rectangle<int> area, int index)
{
    const auto& track = tracks[static_cast<size_t>(index)];
    g.setColour(index % 2 == 0 ? juce::Colour(0xff242a32) : juce::Colour(0xff272d35));
    g.fillRect(area);

    auto header = area.removeFromLeft(190);
    g.setColour(track.colour);
    g.fillRect(header.removeFromLeft(4));
    header.reduce(12, 0);

    g.setColour(text);
    g.setFont(juce::Font(juce::FontOptions(12.5f, juce::Font::bold)));
    g.drawText(track.name, header.removeFromTop(28), juce::Justification::centredLeft);
    g.setColour(secondary);
    g.setFont(juce::Font(juce::FontOptions(10.0f)));
    g.drawText("STEREO  •  INPUT 1–2", header.removeFromTop(18), juce::Justification::centredLeft);

    auto clipArea = area.reduced(5, 12);
    if (index == 0 && recordedSamples.load() > 0)
    {
        const int clipWidth = juce::jmax(110, juce::jmin(clipArea.getWidth(), 110 + recordedSamples.load() / 1600));
        auto clip = clipArea.withWidth(clipWidth);
        g.setColour(juce::Colour(0xff285b83));
        g.fillRoundedRectangle(clip.toFloat(), 5.0f);
        g.setColour(juce::Colour(0xff4b9bd6));
        g.drawRoundedRectangle(clip.toFloat(), 5.0f, 1.0f);
        drawWaveform(g, clip.reduced(4, 5));
    }
    else
    {
        g.setColour(juce::Colour(0xff353c46));
        g.drawRoundedRectangle(clipArea.withWidth(juce::jmin(clipArea.getWidth(), 150)).toFloat(), 4.0f, 1.0f);
    }

    g.setColour(line);
    g.drawHorizontalLine(area.getBottom(), static_cast<float>(area.getX()), static_cast<float>(getWidth()));
}

void MainComponent::paint(juce::Graphics& g)
{
    g.fillAll(bg);

    // Application header and transport bar
    g.setColour(top);
    g.fillRect(0, 0, getWidth(), 58);
    g.setColour(blue);
    g.fillRect(0, 57, getWidth(), 1);

    g.setColour(text);
    g.setFont(juce::Font(juce::FontOptions(22.0f, juce::Font::bold)));
    g.drawText("EC", 20, 10, 30, 27, juce::Justification::centredLeft);
    g.setColour(blueBright);
    g.drawText("STUDIO", 51, 10, 90, 27, juce::Justification::centredLeft);
    g.setColour(secondary);
    g.setFont(juce::Font(juce::FontOptions(9.5f)));
    g.drawText("DIGITAL AUDIO WORKSTATION", 21, 36, 180, 14, juce::Justification::centredLeft);

    g.setColour(panel);
    g.fillRect(0, 59, getWidth(), 54);
    g.setColour(line);
    g.drawHorizontalLine(112, 0.0f, static_cast<float>(getWidth()));

    // Left browser and inspector
    const int sideWidth = 190;
    const int contentTop = 114;
    const int mixerHeight = mixerVisible ? 172 : 0;
    const int contentBottom = getHeight() - mixerHeight;
    g.setColour(juce::Colour(0xff20252c));
    g.fillRect(0, contentTop, sideWidth, contentBottom - contentTop);
    g.setColour(line);
    g.drawVerticalLine(sideWidth, static_cast<float>(contentTop), static_cast<float>(contentBottom));

    g.setColour(secondary);
    g.setFont(juce::Font(juce::FontOptions(10.0f, juce::Font::bold)));
    g.drawText("LIBRARY", 16, 132, 150, 18, juce::Justification::centredLeft);
    g.setColour(text);
    g.setFont(juce::Font(juce::FontOptions(12.0f)));
    g.drawText("All Sounds", 27, 164, 145, 22, juce::Justification::centredLeft);
    g.drawText("Patches", 27, 194, 145, 22, juce::Justification::centredLeft);
    g.drawText("Loops", 27, 224, 145, 22, juce::Justification::centredLeft);
    g.drawText("Instruments", 27, 254, 145, 22, juce::Justification::centredLeft);
    g.setColour(secondary);
    g.setFont(juce::Font(juce::FontOptions(10.0f, juce::Font::bold)));
    g.drawText("TRACK INSPECTOR", 16, 310, 155, 18, juce::Justification::centredLeft);
    g.setColour(text);
    g.setFont(juce::Font(juce::FontOptions(11.0f)));
    g.drawText("Region", 20, 340, 145, 18, juce::Justification::centredLeft);
    g.drawText("Track", 20, 366, 145, 18, juce::Justification::centredLeft);
    g.drawText("Smart Controls", 20, 392, 145, 18, juce::Justification::centredLeft);
    g.setColour(secondary);
    g.setFont(juce::Font(juce::FontOptions(10.0f)));
    g.drawText("EC AUDIO  •  SESSION", 16, contentBottom - 28, 160, 16, juce::Justification::centredLeft);

    // Arrangement area
    const int timelineX = sideWidth;
    const int timelineWidth = getWidth() - timelineX;
    g.setColour(juce::Colour(0xff22272e));
    g.fillRect(timelineX, contentTop, timelineWidth, 48);
    g.setColour(secondary);
    g.setFont(juce::Font(juce::FontOptions(10.0f)));
    for (int bar = 1; bar <= 32; ++bar)
    {
        const int x = timelineX + 20 + (bar - 1) * 48;
        if (x >= getWidth()) break;
        g.setColour(bar % 4 == 1 ? juce::Colour(0xff6c7785) : line);
        g.drawVerticalLine(x, static_cast<float>(contentTop + 19), static_cast<float>(contentBottom));
        if (bar % 4 == 1)
        {
            g.setColour(secondary);
            g.drawText(juce::String(bar), x + 4, contentTop + 4, 30, 13, juce::Justification::centredLeft);
        }
    }
    g.setColour(line);
    g.drawHorizontalLine(contentTop + 47, static_cast<float>(timelineX), static_cast<float>(getWidth()));

    const int laneTop = contentTop + 48;
    const int laneHeight = 88;
    for (int i = 0; i < static_cast<int>(tracks.size()); ++i)
    {
        const int y = laneTop + i * laneHeight;
        if (y >= contentBottom) break;
        drawTrackLane(g, { timelineX, y, timelineWidth, juce::jmin(laneHeight, contentBottom - y) }, i);
    }

    // Playhead and ruler marker
    const double duration = static_cast<double>(playhead.load()) / currentSampleRate;
    const int playX = timelineX + 20 + static_cast<int>(duration * 48.0 * tempo / 60.0 / 4.0);
    g.setColour(juce::Colour(0xffe8edf4));
    g.drawVerticalLine(playX, static_cast<float>(contentTop), static_cast<float>(contentBottom));
    juce::Path marker;
    marker.startNewSubPath(static_cast<float>(playX - 5), static_cast<float>(contentTop));
    marker.lineTo(static_cast<float>(playX + 5), static_cast<float>(contentTop));
    marker.lineTo(static_cast<float>(playX), static_cast<float>(contentTop + 8));
    marker.closeSubPath();
    g.fillPath(marker);

    // Mixer area
    if (mixerVisible)
    {
        const int mixerY = getHeight() - mixerHeight;
        g.setColour(top);
        g.fillRect(0, mixerY, getWidth(), mixerHeight);
        g.setColour(line);
        g.drawHorizontalLine(mixerY, 0.0f, static_cast<float>(getWidth()));
        g.setColour(secondary);
        g.setFont(juce::Font(juce::FontOptions(10.0f, juce::Font::bold)));
        g.drawText("MIXER", 18, mixerY + 9, 80, 16, juce::Justification::centredLeft);

        const int strips = juce::jmin(static_cast<int>(tracks.size()) + 1, 8);
        for (int i = 0; i < strips; ++i)
        {
            const int x = 20 + i * 116;
            const int y = mixerY + 32;
            g.setColour(panel);
            g.fillRoundedRectangle(static_cast<float>(x), static_cast<float>(y), 102.0f, 128.0f, 5.0f);
            g.setColour(i == strips - 1 ? blue : (i < static_cast<int>(tracks.size()) ? tracks[static_cast<size_t>(i)].colour : green));
            g.fillRect(x, y, 102, 3);
            g.setColour(text);
            g.setFont(juce::Font(juce::FontOptions(10.0f, juce::Font::bold)));
            const juce::String stripName = i == strips - 1 ? "Stereo Out" : tracks[static_cast<size_t>(i)].name;
            g.drawText(stripName, x + 5, y + 8, 92, 16, juce::Justification::centred);
            g.setColour(secondary);
            g.setFont(juce::Font(juce::FontOptions(9.0f)));
            g.drawText(i == strips - 1 ? "MASTER" : "AUDIO", x + 5, y + 25, 92, 13, juce::Justification::centred);
            g.setColour(line);
            g.drawRoundedRectangle(static_cast<float>(x + 45), static_cast<float>(y + 46), 12.0f, 60.0f, 3.0f, 1.0f);
            g.setColour(i == strips - 1 ? green : blueBright);
            g.fillRoundedRectangle(static_cast<float>(x + 47), static_cast<float>(y + 70), 8.0f, 34.0f, 2.0f);
            g.setColour(secondary);
            g.drawText("M    S", x + 8, y + 108, 86, 14, juce::Justification::centred);
        }
    }
}

void MainComponent::resized()
{
    projectLabel.setBounds(158, 8, 220, 25);
    statusLabel.setBounds(158, 32, 360, 18);
    timeLabel.setBounds(getWidth() / 2 - 78, 10, 156, 34);

    recordButton.setBounds(getWidth() - 470, 68, 34, 34);
    playButton.setBounds(getWidth() - 428, 68, 34, 34);
    stopButton.setBounds(getWidth() - 386, 68, 34, 34);
    loopButton.setBounds(getWidth() - 335, 68, 58, 34);
    metronomeButton.setBounds(getWidth() - 270, 68, 62, 34);
    tempoBox.setBounds(getWidth() - 198, 70, 82, 30);
    exportButton.setBounds(getWidth() - 108, 68, 88, 34);

    addTrackButton.setBounds(16, 68, 104, 34);
    mixerButton.setBounds(128, 68, 74, 34);
}
